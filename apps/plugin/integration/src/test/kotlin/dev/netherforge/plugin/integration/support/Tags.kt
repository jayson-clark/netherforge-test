package dev.netherforge.plugin.integration.support

import org.junit.jupiter.api.Tag

/**
 * A scenario whose behaviour doesn't depend on the Minecraft version: what it checks is the runtime's, through
 * calls the contract suites already hold every adapter to. A pull request runs it on the newest adapter only (the
 * integration build's `pr` scope); `full` (the default, and nightly) runs it on every version. A scenario that
 * reaches anything a version says its own way (world storage, datapacks, registries, packets, goals) stays
 * untagged, and runs everywhere.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("version-independent")
annotation class VersionIndependent

/**
 * A flaky scenario or step taken out of the normal runs until [issue] (its GitHub issue's URL) is fixed: the
 * `quarantine` tag. Nightly runs quarantined tests on their own, without failing, so a fix shows. Quarantine a step
 * only when no later step builds on it; otherwise the whole scenario.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag("quarantine")
annotation class Quarantine(val issue: String)
