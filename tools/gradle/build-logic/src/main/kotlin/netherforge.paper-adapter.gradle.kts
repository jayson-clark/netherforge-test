import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

/*
 * One Paper adapter: `apps/plugin/paper-<minecraft>`, whose folder name is the
 * one place its Minecraft version is written. Almost all of the adapter is
 * `:plugin:paper-common` (the Paper API only, compiled against the oldest
 * supported version). What reaches into the server itself (Mojang-named) is
 * written once in `apps/plugin/paper-internals/src` and compiled by every
 * adapter against its own server, through paperweight-userdev's dev bundle,
 * together with the adapter's own `src`: the few things its server says its
 * own way (`Mojang`, `Protocol`). Each has two source sets:
 *
 *  - `main`: the `PaperVersion` (found through `ServiceLoader`), in the plugin
 *    jar `NetherForge-<version>-paper-<minecraft>.jar`;
 *  - `bots`: the dev server's fake players, a plugin of their own,
 *    `NetherForgeBots-<version>-paper-<minecraft>.jar`, which only the editor's
 *    dev servers and the integration test install.
 *
 * See the minecraft-versions skill.
 */

plugins {
    id("netherforge.kotlin-jvm")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
    id("io.papermc.paperweight.userdev")
}

val repoRoot: File by rootProject.extra

val minecraft = project.name.removePrefix("paper-")
require(Regex("""\d+(\.\d+){1,2}""").matches(minecraft)) { "A Paper adapter's folder is paper-<minecraft>, not ${project.name}" }

val adapter = extensions.create<PaperAdapterExtension>("paperAdapter")
adapter.sources.convention(minecraft)
adapter.serverBuild.convention(
    adapter.paper.map { paper ->
        Regex("""\.build\.(\d+)""").find(paper)?.groupValues?.get(1)?.toInt()
            ?: throw GradleException("$path: paperAdapter.paper \"$paper\" has no build number; set paperAdapter.serverBuild")
    }
)

/**
 * The Java this version's server needs: 21 up to 1.21.x, 25 from 26.1. The
 * adapter's own code targets it (its server's classes need it anyway); what
 * it shares with every version stays at 21 (netherforge.kotlin-jvm).
 */
val java = if (minecraft.substringBefore('.').toInt() >= 26) 25 else 21
kotlin.compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(java.toString()))
    freeCompilerArgs.set(freeCompilerArgs.get().filterNot { it.startsWith("-Xjdk-release=") } + "-Xjdk-release=$java")
}
tasks.withType<JavaCompile>().configureEach { options.release.set(java) }

/** The server code every adapter shares. */
val internals = repoRoot.resolve("apps/plugin/paper-internals/src")

/** The adapter's own `src/`, or the one [PaperAdapterExtension.sources] names. */
val sourceRoot = adapter.sources.map { repoRoot.resolve("apps/plugin/paper-$it/src") }

// Paper runs Mojang-named since 1.20.5, so the jars are used as built: no reobfuscation.
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

val main: SourceSet = sourceSets["main"]
val bots: SourceSet by sourceSets.creating {
    compileClasspath += main.compileClasspath
}

kotlin.sourceSets["main"].kotlin.setSrcDirs(listOf(internals.resolve("main/kotlin"), sourceRoot.map { it.resolve("main/kotlin") }))
main.resources.setSrcDirs(listOf(internals.resolve("main/resources")))
kotlin.sourceSets["bots"].kotlin.setSrcDirs(listOf(internals.resolve("bots/kotlin"), sourceRoot.map { it.resolve("bots/kotlin") }))
bots.resources.setSrcDirs(emptyList<File>())

/**
 * paper-common compiled again against this version's API, never packaged:
 * it's built against the oldest supported API, and an API this version
 * removed or changed would otherwise only show as a NoSuchMethodError on the
 * server. Part of `check`.
 */
val paperCommon: SourceSet by sourceSets.creating {
    compileClasspath += main.compileClasspath
}
kotlin.sourceSets["paperCommon"].kotlin.setSrcDirs(listOf(project(":plugin:paper-common").layout.projectDirectory.dir("src/main/kotlin")))
paperCommon.resources.setSrcDirs(emptyList<File>())
tasks.named("check") { dependsOn(tasks.named("compilePaperCommonKotlin")) }

dependencies {
    "paperCommonCompileOnly"(rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs").findLibrary("serialization-json").get())
    // PaperInterop's soft dependencies, as paper-common has them.
    "paperCommonCompileOnly"(rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs").findLibrary("vault-api").get())
    "paperCommonCompileOnly"(rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs").findLibrary("placeholderapi").get())
    // Paper's API and the server, Mojang-named; the server brings its own Kotlin-free libraries.
    addProvider("paperweightDevelopmentBundle", adapter.paper.map { "io.papermc.paper:dev-bundle:$it" })
    "implementation"(project(":plugin:paper-common"))
    // The bots run on the plugin jar's classes, kotlinx.serialization included.
    "botsCompileOnly"(rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs").findLibrary("serialization-json").get())
}

/** paper-common's `paper-plugin.yml` templates, with this jar's version and Minecraft version filled in. */
val templates = project(":plugin:paper-common").layout.projectDirectory.dir("src/plugin")

fun Copy.pluginYml(template: String) {
    val version = project.version.toString()
    inputs.property("version", version)
    inputs.property("minecraft", minecraft)
    from(templates.file(template)) {
        rename { "paper-plugin.yml" }
        expand("version" to version, "minecraft" to minecraft)
    }
}

tasks.named<Copy>("processResources") { pluginYml("paper-plugin.yml") }
tasks.named<Copy>("processBotsResources") { pluginYml("bots-paper-plugin.yml") }

tasks.named<Jar>("jar") {
    // Only the shadow jar is loadable; keep the thin one from looking like it.
    archiveClassifier.set("thin")
}

/** `NetherForge-<version>-paper-<minecraft>.jar`: the plugin, with the runtime, format and Kotlin inside. */
val pluginJar = tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("NetherForge")
    archiveClassifier.set("paper-$minecraft")
    mergeServiceFiles()
}

/**
 * `NetherForgeBots-<version>-paper-<minecraft>.jar`: the bots alone. They load
 * with the plugin's own classes (`join-classpath` in their `paper-plugin.yml`),
 * so nothing else goes in.
 */
val botsJar = tasks.register<Jar>("botsJar") {
    description = "Builds the dev server's bots for Paper $minecraft, a plugin of their own"
    archiveBaseName.set("NetherForgeBots")
    archiveClassifier.set("paper-$minecraft")
    from(bots.output)
}

tasks.named("assemble") { dependsOn(pluginJar, botsJar) }

/** This version's Paper server, for the integration test. */
val adapterMinecraft = minecraft
val downloadPaper = tasks.register<DownloadPaper>("downloadPaper") {
    val version = adapterMinecraft
    description = "Downloads the Paper $version server build this adapter is for, cached in the Gradle user home"
    this.minecraft.set(version)
    build.set(adapter.serverBuild)
    jar.set(adapter.serverBuild.map { layout.projectDirectory.file(gradle.gradleUserHomeDir.resolve("caches/netherforge/paper/paper-$version-$it.jar").absolutePath) })
}

// What the integration test (`:plugin:integration`) runs this adapter with.
for ((name, task) in listOf("pluginJar" to pluginJar, "botsJar" to botsJar)) {
    configurations.consumable(name) { outgoing.artifact(task) }
}
configurations.consumable("paperServer") { outgoing.artifact(downloadPaper.flatMap { it.jar }) { builtBy(downloadPaper) } }

/** `node tools/gradle.mjs :plugin:paper-<minecraft>:runServer` for trying the plugin by hand against examples/basic. */
tasks.named<xyz.jpenilla.runpaper.task.RunServer>("runServer") {
    minecraftVersion(minecraft)
    pluginJars(pluginJar.flatMap { it.archiveFile })
    jvmArgs("-Dnetherforge.project=${repoRoot.resolve("examples/basic").absolutePath}")
}
