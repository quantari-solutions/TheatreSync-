plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.paparazzi)
}

android {
    // Store native code compressed: the speech recognizer is large, and this keeps the download small.
    packaging { jniLibs { useLegacyPackaging = true } }
    namespace = "com.theatresync.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.theatresync.app"
        // 29 = Android 10, the first release with AudioPlaybackCapture.
        minSdk = 29
        targetSdk = 35
        versionCode = 23
        versionName = "0.5.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Where "Send to GitHub" opens a pre-filled crash issue (the owner submits it).
        buildConfigField("String", "CRASH_REPO", "\"quantari-solutions/TheatreSync-\"")
        // Public client ID of the owner's GitHub App (device flow). Not a secret.
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"${project.findProperty("githubClientId") ?: ""}\"")
    }

    buildTypes {
        release {
            // Shrink + obfuscate release builds (OWASP MASVS-CODE / smaller APK).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Internal testing: signed with the build machine's debug key so it installs for
            // device tests. Play Store signing keys must come from GitHub secrets, never the repo.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // One APK per phone chip type instead of one with all four: the on-device translator brings
    // ~15 MB of native code per type. arm64 = real phones (owner's Motorola); x86_64 = CI emulator.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        // Newer-library nags are tracked by Dependabot instead of failing builds.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        // Adaptive icons must stay in mipmap-anydpi-v26 (aapt2 rejects them in plain anydpi).
        disable += "ObsoleteSdkInt"
        // Picture in picture opens only when the owner asks (never automatically on leaving the app),
        // so the auto-enter / source-rect advice doesn't apply.
        disable += "PictureInPictureIssue"
        xmlReport = true
        htmlReport = true
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":sync-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.zxing.core) // QR code for TapIn (encoding only, pure Java)
    implementation(libs.mlkit.translate) // on-device subtitle translation (models downloaded on first use)
    // Offline speech recognition for generated subtitles (works on any phone; language pack downloaded once).
    implementation(libs.vosk.android) { exclude(group = "net.java.dev.jna") }
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.cast.framework)
    implementation(libs.androidx.mediarouter)
    // mediarouter pulls in an old Fragment that breaks registerForActivityResult.
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.accessibility)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.compose.ui.test.manifest)
}
