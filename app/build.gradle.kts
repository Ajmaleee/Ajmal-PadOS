plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Source package stays com.ajmal.os; the installed app id differs so it can sit beside Ajmal OS.
    namespace = "com.ajmal.os"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ajmal.pados"
        minSdk = 29
        // Android 10 (API 29): last level before package-visibility filtering, so the app list needs no QUERY_ALL_PACKAGES.
        targetSdk = 29
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK is directly installable.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Intentionally no dependencies: framework APIs only (Kotlin stdlib is added by the plugin).
