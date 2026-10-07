import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Every Kotlin JVM project. Compiled with Java 25 for bytecode Java 21 runs,
 * and against Java 21's library only: the oldest supported Minecraft (1.21.x)
 * runs on Java 21, and the runtime, format and paper-common are in its jar.
 * An adapter for a version that needs a newer Java raises its own target
 * (netherforge.paper-adapter).
 */

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
