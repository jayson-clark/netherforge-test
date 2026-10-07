import java.time.Duration

plugins {
    id("netherforge.kotlin-jvm")
    kotlin("plugin.serialization")
    // The Platform contract suites (src/testFixtures): run against the fake here and against Paper by each adapter.
    `java-test-fixtures`
}

dependencies {
    api(project(":format"))
    implementation(libs.serialization.json)
    // `nf.schedule.cron`: when a cron expression next matches, in a time zone.
    implementation(libs.cron.utils)
    // `nf.http.request`: the client, given a Dns that resolves once and returns only checked addresses.
    implementation(libs.okhttp)
    implementation(libs.luajava)
    implementation(libs.luajava.lua54)
    runtimeOnly(variantOf(libs.luajava.natives) { classifier("natives-desktop") })
    // The debugger speaks DAP through lsp4j. Its Gson is the server's own (Paper's API carries it), never shaded.
    implementation(libs.lsp4j.debug) { exclude(group = "com.google.code.gson") }
    compileOnly(libs.gson)
    testImplementation(libs.gson)
    // The store's SQLite driver is the server's own (every supported Paper bundles xerial's), never shaded.
    compileOnly(libs.sqlite.jdbc)
    testImplementation(libs.sqlite.jdbc)
    // Remote databases' pool and PostgreSQL's driver: the plugin's loader fetches them, so they're never shaded. MySQL's
    // driver is the server's own and is only named by its class. H2 is the stand-in server in tests.
    compileOnly(libs.hikari)
    testImplementation(libs.hikari)
    testImplementation(libs.postgresql)
    testImplementation(libs.h2)

    testFixturesApi(kotlin("test-junit5"))
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    // A bot's dialog inputs are JSON values (format's bridge types).
    testFixturesImplementation(libs.serialization.json)

    testImplementation(project(":plugin:testkit"))
    testImplementation(kotlin("test"))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// The Lua API's bindings, generated from packages/api by `pnpm generate` and committed,
// so this build never needs Node.
sourceSets {
    main {
        kotlin.srcDir("src/generated/kotlin")
        resources.srcDir("src/generated/lua")
    }
}

// Generated code is formatted by its generator.
ktlint {
    filter { exclude { it.file.path.contains("${File.separator}src${File.separator}generated${File.separator}") } }
}

val repoRoot: File by rootProject.extra

tasks.test {
    useJUnitPlatform()
    // A Lua budget bug shows up as a hang; fail instead of waiting forever.
    timeout.set(Duration.ofMinutes(5))
    // luajava loads Lua's native library; Java 25 warns unless that's allowed explicitly.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // The conformance test reads packages/api/generated/api.json; others load examples/. Declared
    // as inputs so the build cache never replays a result from before they changed.
    environment("NETHERFORGE_REPO", repoRoot.absolutePath)
    inputs.file(repoRoot.resolve("packages/api/generated/api.json")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(repoRoot.resolve("examples")).withPathSensitivity(PathSensitivity.RELATIVE)
}
