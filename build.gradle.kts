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
    intellijPlatform {
        clion(runIdeVersion)
        bundledPlugin("intellij.ssh.plugin")
        bundledModule("intellij.platform.coverage")
        bundledModule("intellij.platform.coverage.agent")
        bundledModule("intellij.platform.smRunner")
        bundledModule("intellij.platform.testRunner")
        testFramework(TestFrameworkType.Platform)
        pluginModule(project(":clion"))
    }
    testImplementation("junit:junit:4.13.2")
}

// The optional CLion module's tests reuse this module's platform fixture base
// (XMakePlatformTestCase), so the compiled test classes are published as a consumable
// artifact instead of duplicating the fixture in the module.
val testArtifacts by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
val testJar by tasks.registering(Jar::class) {
    archiveClassifier = "test"
    from(sourceSets.test.get().output)
}
artifacts.add("testArtifacts", testJar)

tasks {
    test {
        useJUnit()
        include("io/xmake/**/**")
        // One fresh JVM per class: the fixture-heavy suites (coverage integration,
        // analysis support) open editors whose RD client deliveries land after
        // disposal, and in a shared container those logged errors fail whichever test
        // runs when they arrive. Isolation is the fix, not a mask - every suite still
        // runs, asserts everything, and fails on real bugs. The plain parser classes
        // pay only a bare JVM start.
        forkEvery = 1
    }
}
