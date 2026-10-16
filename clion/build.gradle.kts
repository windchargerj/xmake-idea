import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(25)
}

// The profiler dump API for the flame graph; the IDE dependency arrives as an exploded
// directory, so the jar is mounted explicitly for compilation. The IDE directory follows the
// same runIdeVersion property the platform dependency resolves with, and a missing jar fails
// here with the reason instead of surfacing later as unresolved Kotlin references.
val runIdeVersion = providers.gradleProperty("runIdeVersion")
val profilerJar = file("../.intellijPlatform/ides/CL-${runIdeVersion.get()}/lib/intellij.profiler.common.jar")
require(profilerJar.exists()) {
    "intellij.profiler.common.jar not found for CL-${runIdeVersion.get()} under .intellijPlatform/ides - " +
        "materialize that IDE once (runIdeVersion: '${runIdeVersion.get()}'), or align the property with the cached IDEs"
}

dependencies {
    compileOnly(project(":"))
    compileOnly(files(profilerJar))

    // The module's tests cover the moved analysis code: the perf dump parser lookup and the
    // native Valgrind session (whose API stays internal to this module, visible to its own
    // test compilation). The platform fixture base (XMakePlatformTestCase) comes from the root
    // module's published test artifact.
    testImplementation(project(":"))
    testImplementation(files(profilerJar))
    testImplementation(project(mapOf("path" to ":", "configuration" to "testArtifacts")))
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        clion(providers.gradleProperty("runIdeVersion"))
        bundledModule("intellij.platform.dap")
        // CLion's memory profiling module: the native Valgrind results panel and console
        // wrapper, compiled against for their API shapes. At runtime the classes stay in
        // CLion's own class loader - an internal module no plugin may depend on - and are
        // reached through that loader; nothing here links against them.
        bundledModule("intellij.cidr.profiling")
        bundledModule("intellij.clion.toolchains")
        bundledPlugin("com.intellij.nativeDebug")
        bundledPlugin("com.intellij.clion-compdb")
        testFramework(TestFrameworkType.Platform)
    }
}
