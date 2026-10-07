package dev.netherforge.plugin.integration.support

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.BridgeEvent
import dev.netherforge.format.bridge.BridgeMethod
import dev.netherforge.format.bridge.CommandParams
import dev.netherforge.format.bridge.HelloResult
import dev.netherforge.format.bridge.JsonRpc
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.ReloadParams
import dev.netherforge.format.bridge.ReloadResult
import dev.netherforge.format.bridge.RpcFrame
import dev.netherforge.format.bridge.RpcMessage
import dev.netherforge.format.bridge.RpcNotification
import dev.netherforge.format.bridge.RpcRequest
import dev.netherforge.format.bridge.RpcResponse
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The editor's end of one bridge connection, speaking JSON-RPC as the editor
 * does: it accepts the plugin's hello, and hands a scenario the hello's
 * params, notifications' params and each console entry on its own. Frames
 * that arrive while waiting for another are kept in [seen], so a later
 * [next] still finds them.
 */
class Editor(socket: Socket) {
    /** What a request got back: whether it succeeded and, if not, why. */
    data class Answer(val ok: Boolean, val error: String?, val code: Int?)

    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

    /** Frames as they arrive, and [CLOSED] when the plugin hangs up. */
    private val frames = LinkedBlockingQueue<Any>()
    private var nextId = 1

    init {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        Thread {
            runCatching {
                while (true) {
                    when (val message = (JsonRpc.decode(reader.readLine() ?: break) as RpcFrame.Single).message) {
                        is RpcRequest -> {
                            val hello = Bridge.hello.decodeParams(message.params)
                            write(RpcResponse.ok(message.id, Bridge.hello.encodeResult(HelloResult(Bridge.PROTOCOL))))
                            frames += hello
                        }
                        is RpcNotification ->
                            if (message.method == Bridge.console.name) {
                                frames.addAll(Bridge.console.decode(message.params))
                            } else if (message.method == Bridge.profiler.name) {
                                frames.addAll(Bridge.profiler.decode(message.params))
                            } else {
                                frames += Bridge.events.single { it.name == message.method }.decode(message.params)!!
                            }
                        else -> frames += message
                    }
                }
            }
            frames += CLOSED
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** Frames that arrived while waiting for something else, oldest first. */
    val seen = mutableListOf<Any>()

    private fun write(message: RpcMessage) {
        synchronized(writer) {
            writer.write(JsonRpc.line(message) + "\n")
            writer.flush()
        }
    }

    /** The first frame [matching] accepts, among those [seen] already or arriving within [seconds]. */
    fun next(seconds: Long = 60, matching: (Any) -> Boolean): Any {
        seen.firstOrNull(matching)?.let {
            seen.remove(it)
            return it
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            val frame = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (matching(frame)) return frame
            seen += frame
        }
        fail("nothing matching within ${seconds}s; saw ${seen.map { it::class.simpleName }}")
    }

    /** Sends a notification: the debugger's DAP messages ([Bridge.dap]) are the editor's only ones. */
    fun <P> notify(event: BridgeEvent<P>, params: P) = write(event.notification(params))

    /** Sends [method] with [params] and waits for its answer, and its result when it succeeded. */
    fun <P, R> request(method: BridgeMethod<P, R>, params: P, seconds: Long = 60): Pair<Answer, R?> {
        val (response, answer) = send(method.request(nextId++, params), seconds)
        return answer to response.result?.let(method::decodeResult)
    }

    /** Sends [method] whatever it is, known or not, and waits for its answer. */
    fun call(method: String, seconds: Long = 60): Answer = send(RpcRequest(JsonPrimitive(nextId++), method), seconds).second

    private fun send(request: RpcRequest, seconds: Long): Pair<RpcResponse, Answer> {
        write(request)
        val response = next(seconds) { it is RpcResponse && it.id == request.id } as RpcResponse
        return response to Answer(response.error == null, response.error?.message, response.error?.code)
    }

    /** Runs a console command, which must be accepted. */
    fun run(line: String) {
        val (answer, _) = request(Bridge.command, CommandParams(line))
        assertTrue(answer.ok, "$line: ${answer.error}")
    }

    /** Runs a console command, whatever it answers. */
    fun tryRun(line: String): Answer = request(Bridge.command, CommandParams(line)).first

    /** Waits for a script's `log(...)` of exactly [parts], tab-separated as `log` joins them. */
    fun logged(vararg parts: String, seconds: Long = 60): Log = next(seconds) { it is Log && it.message == parts.joinToString("\t") } as Log

    /** Reloads [paths] as a save in the editor does, and answers what reloaded. */
    fun reload(vararg paths: String): ReloadResult {
        val (answer, result) = request(Bridge.reload, ReloadParams(paths.toList()))
        assertTrue(answer.ok, answer.error)
        return result!!
    }

    companion object {
        /** What [next] sees once the plugin has closed the connection. */
        val CLOSED = Any()
    }
}
