package dev.netherforge.plugin.async

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The runtime's one pool of worker threads: everything that mustn't hold up
 * the main thread (copying a world's files, the store's reads and batched
 * writes; later HTTP and packages' databases) runs here, never on a thread a
 * feature makes for itself.
 *
 * It lives as long as the plugin, not a session: threads aren't made again
 * at every full reload, and work a session started (the files it writes as it
 * stops) finishes after it ends. What comes back to the main thread goes
 * through [Completions], tagged with the session that asked, so an ended
 * session's results are dropped there rather than here.
 *
 * Bounded twice: [threads] threads (idle ones go after a while), and at most
 * [maxScriptWork] pieces of work scripts started waiting or running at once
 * ([submit] fails past that, which a script sees as `nil, err`). The
 * runtime's own work ([lane]s, saving) is never refused.
 *
 * Nothing that runs here may call into Lua: [dev.netherforge.plugin.lua.LuaHost]
 * refuses any thread but the one that made it.
 */
class Workers(
    threads: Int = THREADS,
    private val maxScriptWork: Int = MAX_SCRIPT_WORK,
    /** A piece of work threw past everything that would have caught it (a lane's, which has no stage to fail): called on the worker. */
    private val uncaught: (Throwable) -> Unit = {}
) : Executor,
    AutoCloseable {
    private val numbers = AtomicInteger()

    private val pool = ThreadPoolExecutor(threads, threads, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { work ->
        Thread(work, "NetherForge worker ${numbers.incrementAndGet()}").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    /** Work given to [execute] and not finished yet; [idle] waits for none. */
    private val lock = Object()
    private var unfinished = 0

    private val scriptWork = AtomicInteger()

    /** Runs [command] on a worker. Throws [RejectedExecutionException] once [close]d. */
    override fun execute(command: Runnable) {
        synchronized(lock) { unfinished++ }
        try {
            pool.execute {
                try {
                    command.run()
                } catch (e: Throwable) {
                    uncaught(e)
                } finally {
                    finished()
                }
            }
        } catch (e: RejectedExecutionException) {
            finished()
            throw e
        }
    }

    private fun finished() = synchronized(lock) {
        unfinished--
        if (unfinished == 0) lock.notifyAll()
    }

    /**
     * Runs [work] off the main thread for a script ([on]: the pool, or one of
     * its [lane]s), as the stage's result; what it throws is the stage's
     * failure. Past [maxScriptWork] at once, the stage fails straight away
     * with [WorkFailed]: a script can't queue work without bound.
     */
    fun <T> submit(on: Executor = this, work: () -> T): CompletableFuture<T> {
        if (scriptWork.incrementAndGet() > maxScriptWork) {
            scriptWork.decrementAndGet()
            return CompletableFuture.failedFuture(
                WorkFailed("the server already has $maxScriptWork pieces of scripts' work waiting; try again later")
            )
        }
        val future = CompletableFuture<T>()
        try {
            on.execute {
                try {
                    future.complete(work())
                } catch (e: Throwable) {
                    future.completeExceptionally(e)
                } finally {
                    scriptWork.decrementAndGet()
                }
            }
        } catch (e: RejectedExecutionException) {
            scriptWork.decrementAndGet()
            future.completeExceptionally(WorkFailed("the server is stopping"))
        }
        return future
    }

    private val lanes = ConcurrentHashMap<String, Executor>()

    /**
     * The lane called [name]: work given to it runs on the pool one piece at
     * a time, in the order it was given (a world's files copied, then deleted;
     * the store's batches committed), across sessions too: there's
     * one lane by each name for the plugin's life. The standard serial executor
     * over this one.
     */
    fun lane(name: String): Executor = lanes.computeIfAbsent(name) { Lane(this) }

    private class Lane(private val workers: Executor) : Executor {
        private val queue = ArrayDeque<Runnable>()
        private var active: Runnable? = null

        @Synchronized
        override fun execute(command: Runnable) {
            queue.addLast(
                Runnable {
                    try {
                        command.run()
                    } finally {
                        next()
                    }
                }
            )
            if (active == null) next()
        }

        @Synchronized
        private fun next() {
            active = queue.removeFirstOrNull()
            active?.let(workers::execute)
        }
    }

    /** Waits until nothing given to the pool is running or waiting; false if [millis] passed first. */
    fun idle(millis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        synchronized(lock) {
            while (unfinished > 0) {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) return false
                lock.wait(left)
            }
        }
        return true
    }

    /** Finishes what's been given (up to [CLOSE_SECONDS]) and stops the threads; nothing more is taken. */
    override fun close() {
        pool.shutdown()
        if (!pool.awaitTermination(CLOSE_SECONDS, TimeUnit.SECONDS)) pool.shutdownNow()
    }

    companion object {
        /** The work is files and sockets, not computing: four keep a disk busy without competing with the server for cores. */
        const val THREADS = 4

        /** Scripts' work at once, waiting or running: plenty for real use, and a loop that starts copies can't fill memory. */
        const val MAX_SCRIPT_WORK = 256

        private const val IDLE_SECONDS = 30L

        /** How long stopping waits for work under way (files being copied, reports written). */
        private const val CLOSE_SECONDS = 30L
    }
}

/**
 * Why a piece of async work failed, in words for the script that waited:
 * what it gets as `err` (a disk that's full, a server that refused the
 * copy). Anything else that escapes the work is a bug, logged, and the script
 * only hears that it failed.
 */
class WorkFailed(message: String) : RuntimeException(message) {
    override fun fillInStackTrace(): Throwable = this
}
