import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.kataa"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "app.kataa"
        // Android 8.0+: covers ~97% of active devices, including low-RAM phones.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    externalNativeBuild {
        cmake { path = file("../native/CMakeLists.txt") }
    }

    // Sideload signing for test builds you install by hand. The keystore lives outside the repo; with no
    // keystore.properties the release APK is unsigned (CI), and Play signing is set up separately at launch.
    val sideload = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        val p = Properties(); f.inputStream().use { p.load(it) }; p
    }
    if (sideload != null) signingConfigs.create("sideload") {
        storeFile = file(sideload.getProperty("storeFile"))
        storePassword = sideload.getProperty("storePassword")
        keyAlias = sideload.getProperty("keyAlias")
        keyPassword = sideload.getProperty("keyPassword")
    }

    // One APK per phone type, so each download carries only the native code that phone runs.
    // 32-bit ARM stays in: many budget phones still run 32-bit Android.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            if (sideload != null) signingConfig = signingConfigs.getByName("sideload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The search model ships inside the APK (scripts/fetch-model.sh puts it in models/).
    sourceSets["main"].assets.directories += rootProject.layout.projectDirectory.dir("models").asFile.path
    androidResources { noCompress += listOf("onnx") }
    // The on-device AI model (scripts/fetch-llm-model.sh), packaged as an uncompressed, 16 KB aligned
    // "native library" so it can be memory-mapped straight from the APK. Not an ELF file: never strip it.
    // -Pkataa.noMind=true builds the small APK; the AI is then added in Settings from two downloaded files.
    if (providers.gradleProperty("kataa.noMind").orNull != "true")
        sourceSets["main"].jniLibs.directories += rootProject.layout.projectDirectory.dir("mind/jni").asFile.path
    packaging { jniLibs { keepDebugSymbols += "**/libkataa_mind.so" } }

    testOptions { unitTests.isIncludeAndroidResources = true }

    packaging {
        // Store .so files uncompressed and 16 KB aligned (Play requirement), loaded straight from the APK.
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation(project(":core")) {
        // Desktop-only pieces of the JVM core: Android uses its own ONNX Runtime and the native database.
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime")
        exclude(group = "org.xerial", module = "sqlite-jdbc")
    }
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.location)
    // Text recognition model is delivered by Google Play services, not bundled: keeps the APK small.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)

    // Renders the real screens on the JVM (no phone needed) to catch crashes and check the look.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit)
}
