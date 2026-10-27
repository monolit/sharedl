plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Подпись релиза берётся из переменных окружения (GitHub Secrets); без них — отладочный ключ
val ksPath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "app.sharedl"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.sharedl"
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
        // Python 3.13 в Chaquopy — только 64-бит
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (ksPath != null) "release" else "debug")
        }
    }
    // нужно, чтобы libffmpeg.so лежал распакованным в nativeLibraryDir
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

chaquopy {
    defaultConfig {
        version = "3.13"   // на машине сборки должен быть python3.13 (или задайте buildPython)
        pip {
            install("yt-dlp")
            install("gallery-dl")
            install("mutagen")   // обложки в m4a/opus без ffmpeg
            install("requests")  // нужен libdl (и gallery-dl)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // только ffmpeg-бинарник (Python берётся из Chaquopy)
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.2")
}
