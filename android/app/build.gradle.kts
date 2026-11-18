plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.theatresync.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.theatresync.app"
        // 29 = Android 10, the first release with AudioPlaybackCapture.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Unsigned in CI. Real signing keys must come from GitHub secrets, never the repo.
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
    testImplementation(libs.junit)
}
