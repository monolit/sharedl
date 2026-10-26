plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "app.sharedl"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.sharedl"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        // Python 3.13 в Chaquopy — только 64-бит
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // только ffmpeg-бинарник (Python берётся из Chaquopy)
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.2")
}
