package dev.netherforge.plugin.integration.support

import dev.netherforge.format.bridge.Problems
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import kotlin.test.assertEquals

/**
 * One integration scenario: a class whose tests are its steps, in `@Order`,
 * against one server running a copy of `examples/basic` with [fixtures]
 * added. The server starts before the first step and stops (cleanly, or the
 * scenario fails) after the last. A step that fails skips the steps after it,
 * which build on it; other scenarios run on regardless.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(Scenario.StopAfterFailure::class)
abstract class Scenario(private vararg val fixtures: String) {
    lateinit var project: TestProject
        private set

    open val server = PaperServer()

    /** The current run of [server]. */
    lateinit var running: PaperServer.Running
        private set

    val editor: Editor get() = running.editor

    /** The server's output, in its folder: a file per scenario, a restart's appended. */
    private val log: String get() = "${javaClass.simpleName}.log"

    /** Changes the project before the server first starts. */
    open fun prepare(project: TestProject) {}

    @BeforeAll
    fun startServer() {
        project = TestProject(*fixtures)
        prepare(project)
        server.prepare()
        java.nio.file.Files.deleteIfExists(server.folder.resolve(log))
        running = server.start(project.root, log)
        // The project loads clean: every resource of the example and the fixtures alike.
        assertEquals(emptyList(), (editor.next { it is Problems } as Problems).problems)
    }

    /** Stops the server, runs [stopped], and starts it again on the same world: what survives a restart. */
    fun restart(stopped: () -> Unit = {}) {
        running.stop()
        stopped()
        running = server.start(project.root, log)
    }

    @AfterAll
    fun stopServer() {
        try {
            if (::running.isInitialized) running.stop()
        } finally {
            if (::project.isInitialized) project.close()
        }
    }

    /** Skips a scenario's steps after one fails: they'd only fail for what it left undone. */
    class StopAfterFailure :
        TestWatcher,
        ExecutionCondition {
        private fun store(context: ExtensionContext) =
            context.root.getStore(ExtensionContext.Namespace.create(StopAfterFailure::class.java, context.requiredTestClass))

        override fun testFailed(context: ExtensionContext, cause: Throwable?) {
            store(context).put(FAILED, context.displayName)
        }

        override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
            if (context.testMethod.isEmpty) return ConditionEvaluationResult.enabled("a scenario")
            val failed = store(context).get(FAILED, String::class.java)
            return if (failed == null) {
                ConditionEvaluationResult.enabled("no step failed")
            } else {
                ConditionEvaluationResult.disabled("an earlier step failed: $failed")
            }
        }

        private companion object {
            const val FAILED = "failed"
        }
    }
}
