plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Подпись релиза берётся из переменных окружения (GitHub Secrets); без них — отладочный ключ
val ksPath: String? = System.getenv("KEYSTORE_PATH")
// curl-cffi 0.17.0 имеет Android wheel для CPython 3.13 только под arm64-v8a.
// Для сборки с x86_64 можно передать -Psharedl.includeX86_64=true (без curl-cffi).
val includeX86_64 = providers.gradleProperty("sharedl.includeX86_64")
    .map { it.toBoolean() }
    .getOrElse(false)

// curl-cffi 0.17.0's Android CPython 3.13 wheel asks for cffi>=2.1.1,
// which Chaquopy's Android repository doesn't publish. Its 2.1.1 minimum
// is needed for CPython 3.15 ABI changes, not for this CPython 3.13 build.
val curlCffiWheel = layout.buildDirectory.file(
    "generated/curl-cffi/curl_cffi-0.17.0+sharedl1-cp313-cp313-android_24_arm64_v8a.whl"
)
val prepareCurlCffiWheel = tasks.register<Exec>("prepareCurlCffiWheel") {
    val prepareScript = rootProject.file("scripts/prepare_curl_cffi_wheel.py")
    inputs.file(prepareScript)
    outputs.file(curlCffiWheel)
    commandLine(
        "python3",
        prepareScript.absolutePath,
        curlCffiWheel.get().asFile.parentFile.absolutePath
    )
}

tasks.configureEach {
    if (!includeX86_64 && name.endsWith("PythonRequirements")) {
        dependsOn(prepareCurlCffiWheel)
        inputs.file(curlCffiWheel)
    }
}

android {
    namespace = "app.sharedl"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.sharedl"
        minSdk = 29
        targetSdk = 34
        versionCode = 10
        versionName = "3.7"
        // Python 3.13 в Chaquopy — только 64-бит.
        // curl-cffi включён в стандартную ARM64-сборку; x86_64 оставлен для отдельной dev-сборки.
        ndk {
            abiFilters += if (includeX86_64) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a")
        }
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
        if (!includeX86_64) extractPackages("curl_cffi")
        pip {
            install("yt-dlp")
            install("gallery-dl")
            install("yt-dlp-ejs")   // скрипты решения YouTube-челленджей (n/sig)
            install("mutagen")   // обложки в m4a/opus без ffmpeg
            install("requests")  // нужен libdl (и gallery-dl)
            if (!includeX86_64) install(curlCffiWheel.get().asFile.absolutePath) // Android wheel с Chaquopy-compatible CFFI metadata
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // только ffmpeg-бинарник (Python берётся из Chaquopy)
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.2")
}
