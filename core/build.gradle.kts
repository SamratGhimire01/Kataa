plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // JVM target: used for fast tests in CI and consumed by the Android app as a plain library.
    // iOS targets (iosArm64, iosSimulatorArm64) are added when the iOS port starts; commonMain
    // deliberately uses no JVM-only APIs so that is a build-file change, not a rewrite.
    jvm {
        // Bytecode level 17 so the same jar works on Android (D8) and any JDK 17+ used by CI.
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.sqlite.jdbc)
        }
    }
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

tasks.withType<Test>().configureEach {
    // Path to the sqlite-vec loadable extension (scripts/fetch-sqlite-vec.sh puts it here).
    systemProperty(
        "kataa.sqliteVec",
        providers.gradleProperty("kataa.sqliteVec")
            .orElse(rootProject.layout.projectDirectory.file("build/native/vec0").asFile.absolutePath)
            .get(),
    )
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
