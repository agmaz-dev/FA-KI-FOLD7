plugins {
    id("com.android.application")
    id("org.JetBrains.kotlin.android")
    id("org.JetBrains.kotlin.plugin.compose")
}

// NPU (Hexagon): in GitHub Actions (CI=true) npu/build-npu.sh builds libggml-hexagon.so + libggml-htp-v*.so in the
// Snapdragon toolchain image (Docker) into app/npu-libs. The app then uses the same NDK as that image, so llama.cpp,
// the NPU part and libc++_shared fit together. Local builds and a failed NPU build = APK without NPU, as before.
val npuScript = rootProject.file("npu/build-npu.sh")
val npuNdk: String? = providers.gradleProperty("foldki.ndk").orNull?.takeIf { it.isNotBlank() }
    ?: if (System.getenv("CI") == "true" && npuScript.exists()) runCatching {
        providers.exec { commandLine("bash", npuScript.path, "ndk"); isIgnoreExitValue = true }
            .standardOutput.asText.get().trim().takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) }
    }.getOrNull() else null

android {
    namespace = "de.foldki.app"
    compileSdk = 36
    npuNdk?.let { ndkVersion = it }

    defaultConfig {
        applicationId = "de.foldki.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 54
        versionName = "4.9.1"
        // Galaxy Z Fold7 and all current phones are 64-bit ARM; keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
        // GGUF models: llama.cpp (from FA Fold KI), always optimised, 16 KB pages, shared C++ runtime
        // (llama/ggml are several libraries; the compute parts CPU + GPU/OpenCL are loaded at runtime).
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                cppFlags += listOf("-O3")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Fixed key, committed on purpose: every build must carry the same signature,
    // otherwise Android refuses to install an update over the existing app.
    // This key is only for privately sideloaded APKs, not for a Play Store release.
    signingConfigs {
        create("sideload") {
            storeFile = file("foldki-release.jks")
            storePassword = "foldki-sideload"
            keyAlias = "foldki"
            keyPassword = "foldki-sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // NPU part (libggml-hexagon.so + DSP libraries libggml-htp-v73…v85.so), built by GitHub Actions into app/npu-libs.
    // Missing folder (local build or failed NPU build) = APK without NPU; GPU/CPU work as before.
    sourceSets["main"].jniLibs.srcDir("npu-libs")
    // Phonon (ab 4.9): libptts_ffi.so, built by GitHub Actions into app/phonon-libs (missing = APK without Phonon).
    sourceSets["main"].jniLibs.srcDir("phonon-libs")

    packaging {
        jniLibs {
            // Unpacked into nativeLibraryDir, so llama.cpp finds its compute parts (libggml-cpu.so, libggml-opencl.so).
            useLegacyPackaging = true
            // Only needed for linking: at runtime the phone's own OpenCL (/vendor/lib64/libOpenCL.so) must be used.
            excludes += "**/libOpenCL.so"
            pickFirsts += "**/libc++_shared.so"
            // DSP code for the Hexagon NPU (not ARM): never strip, the NPU loads it as built.
            keepDebugSymbols += "**/libggml-htp-*.so"
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/**",
                "META-INF/*.version",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.JetBrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.JetBrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Gemini Nano on-device (ML Kit GenAI Prompt API, via Android AICore)
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta1")
    // Live dictation (ab 4.9): ML Kit GenAI speech recognition on the phone, no Google window
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")
    // Phonon voice (ab 4.9): calls libptts_ffi.so through JNA, like xn-ptts' PhononTTS.kt
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    // Optional Gemma 4 E2B on-device (LiteRT-LM). Pinned; needs Kotlin >= 2.3 (metadata 2.4).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.20.0")

    // Reading attachments: PDF text, OCR for photos/scans (offline), photo rotation
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    // Supertonic 3 voices (Elena, Tessa, Caleb) on-device
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")

    // Claude API (official Anthropic Java SDK)
    implementation("com.anthropic:anthropic-java:3.8.0")
    // GitHub access for Claude (same OkHttp version the Anthropic SDK already ships)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Fold/hinge position for the split view (on-device only)
    implementation("androidx.window:window:1.5.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar only has stubs)
    testImplementation("org.json:json:20240303")
}

// NPU part: built once per CI run before the native libraries are packed (never fails the APK build).
val buildNpuLibs = tasks.register<Exec>("buildNpuLibs") {
    onlyIf { npuNdk != null && System.getenv("CI") == "true" }
    commandLine("bash", npuScript.path, "build", file("npu-libs").path)
    isIgnoreExitValue = true
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildNpuLibs) }

// Phonon part (ab 4.9): built once per CI run with Rust + the runner's Android NDK (never fails the APK build).
val phononScript = rootProject.file("phonon/build-phonon.sh")
val buildPhononLibs = tasks.register<Exec>("buildPhononLibs") {
    onlyIf { System.getenv("CI") == "true" && phononScript.exists() }
    commandLine("bash", phononScript.path, file("phonon-libs").path)
    isIgnoreExitValue = true
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildPhononLibs) }

// Run the JVM unit tests (e.g. DocumentParser) in every CI build without touching the workflow.
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn("testReleaseUnitTest") }
