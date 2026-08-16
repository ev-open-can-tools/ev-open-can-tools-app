import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    // The Kotlin/R namespace stays as-is: only the applicationId is the app's
    // identity towards Google and the device, and moving every source file would
    // be churn for no gain.
    namespace = "com.evcantools.app"
    compileSdk = 35

    defaultConfig {
        // Must match the package name registered for Android developer
        // verification, together with the release signing key's SHA-256.
        // A mismatch in either means the APK is not covered by the registration.
        applicationId = "org.ev_open_can_tools.ev_can_app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0-beta.1"
    }

    // Committed debug keystore so every CI build is signed with the same key —
    // sideloaded updates install cleanly instead of being rejected for a
    // signature mismatch. This is a throwaway debug key (password "android"),
    // never used for release signing.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // Release identity. Never committed: this key, together with the
        // applicationId, is what Google's developer verification is registered
        // against, so whoever holds it can publish as this developer. CI writes
        // it from secrets; locally it is absent and release builds stay unsigned.
        create("release") {
            val storePath = System.getenv("EVCAN_KEYSTORE")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = System.getenv("EVCAN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("EVCAN_KEY_ALIAS")
                keyPassword = System.getenv("EVCAN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Only sign when the keystore is actually present, so `assembleRelease`
            // still runs on a machine without it instead of failing the build.
            if (System.getenv("EVCAN_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
