plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "com.theatresync.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.theatresync.app"
        // 29 = Android 10, the first release with AudioPlaybackCapture.
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "0.4.6"
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
        xmlReport = true
        htmlReport = true
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":sync-core"))
    implementation(libs.androidx.core.ktx)
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
