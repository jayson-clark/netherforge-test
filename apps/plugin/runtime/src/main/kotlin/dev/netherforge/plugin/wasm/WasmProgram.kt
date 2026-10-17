package dev.netherforge.plugin.wasm

import dev.netherforge.plugin.api.LuaHandle
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Why a program ended, as its `end` event's `reason` says it. */
enum class ProgramEnd(val luaName: String) {
    EXITED("exited"),
    STOPPED("stopped"),
    FAILED("failed"),
    LIMIT("limit"),
    UNLOADED("unloaded")
}

/** What a program's thread hands the main thread: a line it printed, or its end. */
sealed interface ProgramOutput {
    data class Line(val text: String, val stream: String) : ProgramOutput

    data class Ended(val reason: ProgramEnd, val exitCode: Int? = null, val detail: String? = null) : ProgramOutput
}

/**
 * One program started with `nf.wasm.start`, its [owner] scope's. Its thread ([WasmModules] runs it) reads [input]
 * and writes lines into [output], waiting for [room] (one permit per line, `wasm.max-queued-lines`) while scripts
 * haven't taken them: that wait is the backpressure, so nothing it prints is dropped. The main thread takes lines
 * each tick, up to `wasm.lines-per-tick`, and ends it. Only the main thread touches [ended].
 */
class WasmProgram(
    val number: Long,
    val handle: LuaHandle.WasmProcess,
    val owner: Int,
    val namespace: String,
    /** The module, as the server names it. */
    val key: String,
    maxQueuedLines: Int,
    val maxLineBytes: Int
) {
    val output = ConcurrentLinkedQueue<ProgramOutput>()
    val room = Semaphore(maxQueuedLines)
    val input = ProgramInput()

    /** Set once its end has been handed to scripts (main thread). */
    var ended = false

    /** Its thread, while it runs; set and cleared under the program's lock, so [stopThread] never reaches another program. */
    private var thread: Thread? = null

    /** Runs [body] on this thread as the program's run; an interrupt that came as it finished is cleared. */
    fun <T> running(body: () -> T): T {
        synchronized(this) { thread = Thread.currentThread() }
        try {
            return body()
        } finally {
            synchronized(this) { thread = null }
            Thread.interrupted()
        }
    }

    /** Interrupts its thread, while it runs. */
    fun stopThread() = synchronized(this) { thread?.interrupt() }

    /** Lines the test's stand-in hasn't answered yet, for a stubbed module (main thread). */
    val stubLines = ArrayDeque<String>()

    /** Whether it was started for a stub, which runs nothing. */
    var stubbed = false

    /** Whether the thread is waiting to read input. */
    @Volatile private var reading = false

    /** Whether the thread is waiting for [room] to print. */
    @Volatile private var printing = false

    /** Whether the thread has finished (or never started). */
    @Volatile var finished = false

    /**
     * Whether it can go no further on its own: done, waiting for scripts to take its lines, or waiting for input
     * when none has been sent (a line just sent, which it hasn't woken for yet, doesn't count). Tests wait for it.
     */
    val quiet: Boolean get() = finished || printing || (reading && input.drained)

    /** Queues a line from the thread, waiting for [room]. */
    fun printed(text: String, stream: String) {
        printing = true
        try {
            room.acquire()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("the program was stopped")
        } finally {
            printing = false
        }
        output += ProgramOutput.Line(text, stream)
    }

    /** What the program reads: the lines scripts `send`, until its input is closed. From the program's thread. */
    inner class ProgramInput : InputStream() {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val closed = AtomicBoolean()
        private var current: ByteArray? = null
        private var at = 0

        /** Whether scripts may still send (main thread's view). */
        val open: Boolean get() = !closed.get()

        /** Whether nothing sent is waiting to be read. */
        val drained: Boolean get() = queue.isEmpty()

        fun send(line: String) {
            queue.put((line + "\n").toByteArray(StandardCharsets.UTF_8))
        }

        /** The end of the file, after what's queued. False when it was already closed. */
        fun finish(): Boolean {
            if (!closed.compareAndSet(false, true)) return false
            queue.put(END)
            return true
        }

        override fun read(): Int {
            val buffer = ByteArray(1)
            return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var chunk = current
            if (chunk == null || at >= chunk.size) {
                chunk = queue.poll() ?: run {
                    reading = true
                    try {
                        queue.take()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("the program was stopped")
                    } finally {
                        reading = false
                    }
                }
                if (chunk === END) {
                    // Every later read is the end too.
                    queue.put(END)
                    return -1
                }
                current = chunk
                at = 0
            }
            val n = minOf(len, chunk.size - at)
            System.arraycopy(chunk, at, b, off, n)
            at += n
            return n
        }

        override fun available(): Int = current?.let { it.size - at } ?: 0
    }

    /** One of the program's output streams, cut into lines (LF or CRLF; a long one into pieces of [maxLineBytes]). */
    inner class LineOutput(private val stream: String) : OutputStream() {
        private val line = ByteArrayOutputStream()

        override fun write(b: Int) {
            when {
                b == '\n'.code -> emit()
                else -> {
                    line.write(b)
                    if (line.size() >= maxLineBytes) emit()
                }
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) write(b[i].toInt())
        }

        private fun emit() {
            val bytes = line.toByteArray()
            line.reset()
            val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            printed(String(bytes, 0, end, StandardCharsets.UTF_8), stream)
        }

        /** What's left without a line ending, at the end. */
        fun finish() {
            if (line.size() > 0) emit()
        }
    }

    private companion object {
        val END = ByteArray(0)
    }
}
