package dev.netherforge.plugin.wasm

import com.dylibso.chicory.runtime.Instance
import dev.netherforge.plugin.api.TestWasmStub
import java.util.concurrent.RejectedExecutionException

/**
 * One instance a script made with `nf.wasm.instantiate`, its [owner] scope's: the Chicory instance, the memory it
 * holds against the server's budget ([pages]), and its [lane], which runs its calls one at a time. Or, in a test run,
 * a [stub] standing in for one, which holds nothing.
 *
 * [closed] is read from any thread: the main thread closes one (a script, its scope going), and so does its own
 * call when it traps or runs out of time.
 */
class WasmInstanceRecord private constructor(
    val number: Long,
    val owner: Int,
    val namespace: String,
    /** The module, as the server names it. */
    val key: String,
    private val compiled: CompiledModule?,
    @Volatile private var held: CompiledModule.Running?,
    private val pages: Long,
    threads: WasmThreads,
    val stub: TestWasmStub?
) {
    constructor(
        number: Long,
        owner: Int,
        namespace: String,
        key: String,
        module: CompiledModule,
        running: CompiledModule.Running,
        pages: Long,
        threads: WasmThreads
    ) : this(number, owner, namespace, key, module, running, pages, threads, null)

    val module: CompiledModule get() = checkNotNull(compiled) { "a stand-in has no module" }

    val instance: Instance get() = checkNotNull(held) { "a stand-in has no instance" }.instance

    val lane = WasmThreads.Lane(threads.calls)

    @Volatile var closed = false
        private set

    /** The thread running a call in it now, to interrupt when it's closed; set and cleared under the record's lock. */
    private var running: Thread? = null

    /**
     * Runs [call] on this thread as the instance's call: [close] interrupts it while it runs, and never after (the
     * pool thread goes on to other instances' calls), an interrupt that came as it finished cleared.
     */
    fun <T> running(call: () -> T): T {
        synchronized(this) { running = Thread.currentThread() }
        try {
            return call()
        } finally {
            synchronized(this) { running = null }
            Thread.interrupted()
        }
    }

    /**
     * Closes it: a call running now is interrupted, and once it has stopped (after it on the lane) what the
     * instance holds is let go of and its memory given back to [budget]. Once only.
     */
    fun close(budget: WasmMemoryBudget) {
        synchronized(this) {
            if (closed) return
            closed = true
            running?.takeIf { it !== Thread.currentThread() }?.interrupt()
        }
        val release = Runnable {
            held?.close()
            // The shell stays (its module still answers); the instance and its memory go.
            budget.release(pages)
            held = null
        }
        try {
            lane.execute(release)
        } catch (_: RejectedExecutionException) {
            // The threads are gone (the session stopped): nothing runs in it any more.
            release.run()
        }
    }

    companion object {
        /** A stand-in for an instance of [key], answered by [stub]. */
        fun stub(number: Long, owner: Int, namespace: String, key: String, stub: TestWasmStub, threads: WasmThreads) =
            WasmInstanceRecord(number, owner, namespace, key, null, null, 0, threads, stub)
    }
}
