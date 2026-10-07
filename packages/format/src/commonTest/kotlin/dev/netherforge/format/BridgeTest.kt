package dev.netherforge.format

import dev.netherforge.format.bridge.BotsExtension
import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.BridgeExtension
import dev.netherforge.format.bridge.BridgeMethod
import dev.netherforge.format.bridge.BridgeThread
import dev.netherforge.format.bridge.ConsoleEntry
import dev.netherforge.format.bridge.HelloParams
import dev.netherforge.format.bridge.JsonRpc
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.LogLevel
import dev.netherforge.format.bridge.RpcError
import dev.netherforge.format.bridge.RpcFrame
import dev.netherforge.format.bridge.RpcInvalid
import dev.netherforge.format.bridge.RpcMessage
import dev.netherforge.format.bridge.RpcNotification
import dev.netherforge.format.bridge.RpcRequest
import dev.netherforge.format.bridge.RpcResponse
import dev.netherforge.format.bridge.Status
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The recorded session in `packages/format/testdata/bridge/` is the protocol's contract
 * fixture: every side of the bridge (this, the plugin, the editor) must read
 * each line and write it back identically.
 */
class BridgeTest {
    private val lines = TestFiles.read("packages/format/testdata/bridge/session.ndjson")!!.lines().filter { it.isNotBlank() }

    private fun messages(frame: RpcFrame): List<RpcMessage> = when (frame) {
        is RpcFrame.Single -> listOf(frame.message)
        is RpcFrame.Batch -> frame.messages
    }

    private fun line(frame: RpcFrame) = when (frame) {
        is RpcFrame.Single -> JsonRpc.line(frame.message)
        is RpcFrame.Batch -> JsonRpc.line(frame.messages)
    }

    @Test
    fun recordedSessionRoundTrips() {
        for (line in lines) {
            val frame = JsonRpc.decode(line)
            assertTrue(messages(frame).none { it is RpcInvalid }, line)
            assertEquals(line, line(frame))
        }
    }

    @Test
    fun everyRecordedMessageHasItsDeclaredShape() {
        val methods = (listOf(Bridge.hello) + Bridge.allRequests).associateBy { it.name }
        val asked = mutableMapOf<String, BridgeMethod<*, *>>()
        var answered = 0
        for (message in lines.flatMap { messages(JsonRpc.decode(it)) }) {
            when (message) {
                is RpcRequest -> {
                    val method = methods[message.method] ?: continue
                    asked[message.id.toString()] = method
                    // Params written by the declared type are exactly what was recorded.
                    @Suppress("UNCHECKED_CAST")
                    val typed = method as BridgeMethod<Any?, Any?>
                    val params = runCatching { typed.decodeParams(message.params) }.getOrNull() ?: continue
                    assertEquals(message.params, typed.encodeParams(params), message.method)
                }
                is RpcResponse -> {
                    // An unknown method's answer is an error; anything else answers what was asked.
                    val method = asked[message.id.toString()] ?: continue
                    message.result?.let {
                        method.decodeResult(it)
                        answered++
                    }
                }
                is RpcNotification -> {
                    Bridge.events.firstOrNull { it.name == message.method }?.decode(message.params)
                        ?: Bridge.streams.single { it.name == message.method }.decode(message.params)
                }
                is RpcInvalid -> error("invalid: $message")
            }
        }
        assertTrue(answered > 10)
        val hello = Bridge.hello.decodeParams(JsonRpc.decode(lines.first()).let { (it as RpcFrame.Single).message as RpcRequest }.params)
        assertEquals(Bridge.PROTOCOL, hello.protocol, "the recorded hello is this protocol's; the editor's tests check theirs against it")
    }

    @Test
    fun theConsoleStreamCarriesItsEntriesInOrder() {
        val notification = Bridge.console.notification(listOf(Log(LogLevel.INFO, "a"), Log(LogLevel.WARN, "b")))
        assertEquals(
            """{"jsonrpc":"2.0","method":"console","params":{"items":[{"type":"log","level":"info","message":"a"},{"type":"log","level":"warn","message":"b"}]}}""",
            JsonRpc.line(notification)
        )
        assertEquals(listOf<ConsoleEntry>(Log(LogLevel.INFO, "a"), Log(LogLevel.WARN, "b")), Bridge.console.decode(notification.params))
    }

    @Test
    fun aFieldFromANewerVersionIsIgnored() {
        assertEquals(
            Status(listOf("alex"), 2),
            Bridge.status.decode(Bridge.json.parseToJsonElement("""{"players":["alex"],"instances":2,"tps":19.9}"""))
        )
    }

    @Test
    fun whatIsNotAMessageIsAnsweredAsTheSpecSays() {
        fun invalid(line: String): RpcInvalid = assertIs<RpcInvalid>((JsonRpc.decode(line) as RpcFrame.Single).message)
        assertEquals(JsonRpc.PARSE_ERROR, invalid("""{"jsonrpc":"2.0","method":""").error.code)
        assertEquals(JsonNull, invalid("""{"jsonrpc":"2.0","method":""").id)
        assertEquals(JsonRpc.INVALID_REQUEST, invalid("[]").error.code)
        assertEquals(JsonRpc.INVALID_REQUEST, invalid("""{"jsonrpc":"1.0","id":1,"method":"ping"}""").error.code)
        assertEquals(JsonPrimitive(1), invalid("""{"jsonrpc":"1.0","id":1,"method":"ping"}""").id)
        assertEquals(JsonRpc.INVALID_REQUEST, invalid("""{"jsonrpc":"2.0","id":1,"method":7}""").error.code)
        assertEquals(JsonRpc.INVALID_REQUEST, invalid("""{"jsonrpc":"2.0","id":1,"method":"ping","params":3}""").error.code)
        assertEquals(JsonRpc.INVALID_REQUEST, invalid("""{"jsonrpc":"2.0","id":true,"method":"ping"}""").error.code)
        assertEquals(
            JsonRpc.INVALID_REQUEST,
            invalid("""{"jsonrpc":"2.0","id":1,"result":1,"error":{"code":1,"message":"x"}}""").error.code
        )
        assertEquals(JsonRpc.INVALID_REQUEST, invalid("""{"jsonrpc":"2.0","id":1,"error":{"code":"x","message":"x"}}""").error.code)

        // A batch's elements are read one by one: a bad one doesn't spoil the rest.
        val batch =
            assertIs<RpcFrame.Batch>(JsonRpc.decode("""[{"jsonrpc":"2.0","id":"a","method":"ping"},1,{"jsonrpc":"2.0","method":"note"}]"""))
        assertEquals(RpcRequest(JsonPrimitive("a"), "ping"), batch.messages[0])
        assertIs<RpcInvalid>(batch.messages[1])
        assertEquals(RpcNotification("note"), batch.messages[2])

        assertEquals(
            RpcResponse(JsonPrimitive(4), error = RpcError(-32000, "no", JsonObject(emptyMap()))),
            (JsonRpc.decode("""{"jsonrpc":"2.0","id":4,"error":{"code":-32000,"message":"no","data":{}}}""") as RpcFrame.Single).message
        )
        assertFailsWith<IllegalArgumentException> { RpcResponse(JsonPrimitive(1)) }
    }

    @Test
    fun aRequestWithoutParamsIsSentWithoutThem() {
        assertEquals("""{"jsonrpc":"2.0","id":3,"method":"instances"}""", JsonRpc.line(Bridge.instances.request(3, Unit)))
        assertEquals(JsonNull, Bridge.stopParticleEffects.encodeResult(Unit))
        assertEquals(
            HelloParams("t", 1, "v", "26.3", "/p"),
            Bridge.hello.decodeParams(Bridge.hello.encodeParams(HelloParams("t", 1, "v", "26.3", "/p")))
        )
    }

    @Test
    fun extensionsLiveUnderTheirNamespaceAndOnlyBridgeRequestsSkipTheMainThread() {
        assertEquals(listOf("bots"), Bridge.extensions.map { it.namespace })
        assertTrue(BotsExtension.extension.methods.all { it.name.startsWith("bots/") })
        assertFailsWith<IllegalArgumentException> { BridgeExtension("x", listOf(Bridge.ping)) }
        assertTrue(Bridge.requests.none { '/' in it.name }, "the core protocol has no namespaced methods")
        assertEquals(listOf("ping", "profiler_subscribe"), Bridge.allRequests.filter { it.thread == BridgeThread.BRIDGE }.map { it.name })
        val names =
            (listOf(Bridge.hello) + Bridge.allRequests).map { it.name } + Bridge.events.map { it.name } + Bridge.streams.map { it.name }
        assertEquals(names.toSet().size, names.size, "two methods share a name")
    }

    @Test
    fun noConsoleEntryHasAFieldNamedLikeTheDiscriminator() {
        // `type` says which subclass a JSON object is; a field of that name can't be encoded at all.
        fun check(descriptor: SerialDescriptor, seen: MutableSet<String> = mutableSetOf()) {
            if (!seen.add(descriptor.serialName)) return
            if (descriptor.kind == PolymorphicKind.SEALED) {
                for (sub in descriptor.getElementDescriptor(1).elementDescriptors) {
                    assertEquals(-3, sub.getElementIndex("type"), "${sub.serialName} has a field named type")
                    check(sub, seen)
                }
            }
            for (element in descriptor.elementDescriptors) check(element, seen)
        }
        for (method in Bridge.allRequests) {
            check(method.params.descriptor)
            check(method.result.descriptor)
        }
        check(Bridge.console.item.descriptor)
    }
}
