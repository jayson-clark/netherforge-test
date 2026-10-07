package dev.netherforge.plugin

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.HelloParams
import dev.netherforge.format.bridge.HelloResult
import dev.netherforge.format.bridge.JsonRpc
import dev.netherforge.format.bridge.RpcResponse
import dev.netherforge.plugin.bridge.BridgeClient
import dev.netherforge.plugin.bridge.FrameReader
import dev.netherforge.plugin.bridge.FrameTooLargeException
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The cap on a frame from the editor, before and after the hello: the reader on its own, and the client over a real socket. */
class BridgeFrameLimitTest {
    @Test
    fun `the reader splits frames on newlines, drops a carriage return and ends cleanly`() {
        val reader = FrameReader(ByteArrayInputStream("one\r\n\ntwo\nlast".toByteArray()), 100)
        assertEquals(listOf("one", "", "two", "last"), generateSequence { reader.readFrame() }.toList())
        assertNull(reader.readFrame())
    }

    @Test
    fun `a frame at the limit reads and one byte over throws without reading on`() {
        val ok = "a".repeat(10)
        val reader = FrameReader(ByteArrayInputStream("$ok\n${ok}b\nnext\n".toByteArray()), 10)
        assertEquals(ok, reader.readFrame())
        val error = assertFailsWith<FrameTooLargeException> { reader.readFrame() }
        assertEquals(10, error.limit)
    }

    @Test
    fun `the limit can grow between frames, and multi-byte text counts in bytes`() {
        val reader = FrameReader(ByteArrayInputStream("ab\nééé\n".toByteArray()), 2)
        assertEquals("ab", reader.readFrame())
        assertFailsWith<FrameTooLargeException> { reader.readFrame() }
        val again = FrameReader(ByteArrayInputStream("ééé\n".toByteArray()), 2)
        again.limit = 6
        assertEquals("ééé", again.readFrame())
    }

    private class Harness(val listener: ServerSocket) : AutoCloseable {
        val problems = LinkedBlockingQueue<String>()
        val connected = LinkedBlockingQueue<Unit>()
        val client = BridgeClient(
            port = listener.localPort,
            hello = { HelloParams("secret", Bridge.PROTOCOL, "0.1.0", "26.3", "/p") },
            onFrame = {},
            onConnected = { connected += Unit },
            onProblem = { problems += it }
        )

        fun accept(): Socket {
            listener.soTimeout = 10_000
            return listener.accept().also { it.soTimeout = 10_000 }
        }

        fun problem(): String = problems.poll(10, TimeUnit.SECONDS) ?: fail("no problem logged within 10s")

        override fun close() {
            client.stop()
            listener.close()
        }
    }

    /** Reads the hello's line off [socket]. */
    private fun readHello(socket: Socket) {
        val line = StringBuilder()
        while (true) {
            val byte = socket.getInputStream().read()
            if (byte < 0 || byte == '\n'.code) break
            line.append(byte.toChar())
        }
        assertTrue("\"hello\"" in line, "$line")
    }

    /** Whether the peer has closed: reads the end of the stream, or a reset (it closed with our bytes unread). */
    private fun closedByPeer(socket: Socket): Boolean = try {
        generateSequence { socket.getInputStream().read() }.first { it < 0 } < 0
    } catch (_: java.io.IOException) {
        true
    }

    private fun OutputStream.fill(length: Int) {
        val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
        var left = length
        try {
            while (left > 0) {
                val n = minOf(left, chunk.size)
                write(chunk, 0, n)
                left -= n
            }
            write('\n'.code)
            flush()
        } catch (_: java.io.IOException) {
            // The client closed on us part way, which is the point.
        }
    }

    @Test
    fun `a frame over the small cap before the answer to the hello closes the connection and is logged`() {
        ServerSocket(0).use { listener ->
            Harness(listener).use { harness ->
                harness.client.start()
                harness.accept().use { socket ->
                    readHello(socket)
                    socket.getOutputStream().fill(BridgeClient.MAX_FRAME_BEFORE_HELLO + 1)
                    assertTrue("before answering the hello" in harness.problem())
                    assertTrue(closedByPeer(socket), "the client closed")
                }
                assertTrue(harness.connected.isEmpty(), "never accepted")
                // And it retries.
                harness.accept().use { readHello(it) }
            }
        }
    }

    @Test
    fun `after the hello the larger cap holds, and a frame past it closes the connection and is logged`() {
        ServerSocket(0).use { listener ->
            Harness(listener).use { harness ->
                harness.client.start()
                harness.accept().use { socket ->
                    readHello(socket)
                    val answer = RpcResponse.ok(JsonPrimitive(0), Bridge.hello.encodeResult(HelloResult(Bridge.PROTOCOL)))
                    socket.getOutputStream().write((JsonRpc.line(answer) + "\n").toByteArray())
                    socket.getOutputStream().flush()
                    assertEquals(Unit, harness.connected.poll(10, TimeUnit.SECONDS), "accepted")
                    // Past the pre-hello cap is fine now (the line isn't JSON, so it's a parse error, not a drop).
                    socket.getOutputStream().fill(BridgeClient.MAX_FRAME_BEFORE_HELLO + 1)
                    assertTrue(harness.problems.isEmpty())
                    socket.getOutputStream().fill(BridgeClient.MAX_FRAME + 1)
                    assertTrue("closing the connection" in harness.problem())
                    assertTrue(closedByPeer(socket), "the client closed")
                }
            }
        }
    }
}
