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
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.sqlite.jdbc)
            implementation(libs.onnxruntime)
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
    val nativeDir = rootProject.layout.projectDirectory.dir("native/build/host").asFile
    systemProperty("kataa.nativeLib", listOf("libkataa_sqlite.so", "libkataa_sqlite.dylib").map { nativeDir.resolve(it) }.firstOrNull { it.exists() }?.absolutePath ?: "")
    providers.gradleProperty("kataa.driver").orNull?.let { systemProperty("kataa.driver", it) }
    systemProperty("kataa.modelDir", rootProject.layout.projectDirectory.dir("models/bge-small-en-v1.5").asFile.absolutePath)
    maxHeapSize = "1g"
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
