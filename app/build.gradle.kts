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
        ndk {
            // 32-bit ARM stays in: many budget phones still run 32-bit Android.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=none") }
        }
    }

    externalNativeBuild {
        cmake { path = file("../native/CMakeLists.txt") }
    }

    buildTypes {
        release {
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
}
