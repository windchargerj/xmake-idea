import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

val pluginVersion = providers.gradleProperty("pluginVersion")
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild")
val runIdeVersion = providers.gradleProperty("runIdeVersion")
val localChangeNotes = providers.fileContents(layout.projectDirectory.file("change-notes.html")).asText
val localDescription = providers.fileContents(layout.projectDirectory.file("description.html")).asText

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.changelog") version "2.5.0"
}

group = "io.xmake"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(25)
}

intellijPlatform {
    pluginConfiguration {
        version = pluginVersion
        changeNotes = localChangeNotes
        description = localDescription
        ideaVersion {
            sinceBuild = pluginSinceBuild
        }
    }

    pluginVerification.ides {
        select {
            types = listOf(
                IntelliJPlatformType.CLion,
                IntelliJPlatformType.IntellijIdea
            )
            sinceBuild = pluginSinceBuild
        }
    }
}

dependencies {
    // The IDE dependency arrives as an exploded directory; the profiler API lives in lib/*.jar
    // which the platform classloader exposes to every plugin at runtime, but gradle needs the
    // jar on the compile classpath explicitly.
    compileOnly(
        files(
            file(".intellijPlatform/ides/CL-2026.2/lib/intellij.profiler.common.jar"),
        ),
    )
    intellijPlatform {
        clion(runIdeVersion)
        bundledPlugin("intellij.ssh.plugin")
        bundledModule("intellij.platform.coverage")
        bundledModule("intellij.platform.coverage.agent")
        testFramework(TestFrameworkType.Platform)
        pluginModule(project(":clion-debug"))
    }
    testImplementation("junit:junit:4.13.2")
    testCompileOnly(files(file(".intellijPlatform/ides/CL-2026.2/lib/intellij.profiler.common.jar")))
}

tasks {
    test {
        useJUnit()
        include("io/xmake/**/**")
    }
}
