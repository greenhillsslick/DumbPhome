plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dumbos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dumbos"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.0.5"
    }

    /**
     * Release signing. The keystore in signing/ is committed on purpose: dumbOS
     * has no distribution behind it and a stable key means builds from this repo
     * update each other instead of being "installed over" with a signature
     * mismatch. Replace it with your own (and keep it private) before shipping
     * anything you care about.
     */
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/dumbos-release.jks")
            storePassword = System.getenv("DUMBOS_STORE_PASSWORD") ?: "dumbos-release"
            keyAlias = System.getenv("DUMBOS_KEY_ALIAS") ?: "dumbos"
            keyPassword = System.getenv("DUMBOS_KEY_PASSWORD") ?: "dumbos-release"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    // None. dumbOS is framework APIs only, by design.
}
