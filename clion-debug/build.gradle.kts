plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform.module")
}

group = "io.xmake.debug"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

configurations.configureEach {
    if (name.endsWith("RuntimeClasspath", ignoreCase = true)) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions.set(false)

    // Disable plugin verification and runIDE for the debug module
    pluginVerification {
        ides { }
    }
}

dependencies {
    intellijPlatform {
        clion(providers.gradleProperty("runIdeVersion"))
        bundledPlugin("com.intellij.nativeDebug")
    }
}

// Disable runIde for CLion module (should not run IDE from debug module)
tasks.matching { task -> task.name.contains("runIde") }.configureEach {
    enabled = false
}
