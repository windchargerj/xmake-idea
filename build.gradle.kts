import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

val pluginVersion = providers.gradleProperty("pluginVersion")
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild")
val runIdeVersion = providers.gradleProperty("runIdeVersion")

// read local workspace file to string
val localChangeNotes: String = file("${projectDir}/change-notes.html").readText(Charsets.UTF_8)
val localDescription: String = file("${projectDir}/description.html").readText(Charsets.UTF_8)

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.changelog") version "2.5.0"
    kotlin("plugin.serialization") version "2.3.0"
}

group = "io.xmake"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions.set(false)

    pluginConfiguration {
        version = pluginVersion
        changeNotes = localChangeNotes
        description = localDescription
        ideaVersion {
            sinceBuild = pluginSinceBuild
        }
    }

    caching.ides {
        enabled = true
        path = layout.projectDirectory.dir(".intellijPlatform/ides")
    }
    pluginVerification.ides {
        create(IntelliJPlatformType.CLion, runIdeVersion)
        create(IntelliJPlatformType.IntellijIdea, runIdeVersion)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    intellijPlatform {
        clion(runIdeVersion) { useCache }
        bundledPlugin("com.intellij.nativeDebug")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("junit:junit:4.13.2")
}

tasks {
    test {
        useJUnit()
        include("io/xmake/**/**")
    }
}

// Keep the existing CLion debug jar packaging until it is migrated to a plugin module.
tasks.named("compileKotlin") {
    dependsOn(":clion-debug:build", ":clion-debug:copyToPluginResources")
}

tasks.named("build") {
    dependsOn(":clion-debug:build", ":clion-debug:copyToPluginResources")
}

tasks.named("classes") {
    dependsOn(":clion-debug:build", ":clion-debug:copyToPluginResources")
}

tasks.named("processResources") {
    dependsOn(":clion-debug:copyToPluginResources")
}

tasks.named("jar") {
    dependsOn(":clion-debug:build", ":clion-debug:copyToPluginResources")
}
