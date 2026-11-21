plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.foldki.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.foldki.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 44
        versionName = "4.0"
        // Galaxy Z Fold7 and all current phones are 64-bit ARM; keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
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

    packaging {
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
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Gemini Nano on-device (ML Kit GenAI Prompt API, via Android AICore)
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta1")

    // Optional Gemma 4 E2B on-device (LiteRT-LM). Pinned; needs Kotlin >= 2.3 (metadata 2.4).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")

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

// Run the JVM unit tests (e.g. DocumentParser) in every CI build without touching the workflow.
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn("testReleaseUnitTest") }
