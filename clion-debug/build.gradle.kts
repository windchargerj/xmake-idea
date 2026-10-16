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

dependencies {
    compileOnly(project(":"))

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
    }
}
