plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kody.scrollblock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kody.scrollblock"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "2.1"
    }

    // One fixed key, so new builds install over the old one without uninstalling.
    signingConfigs {
        getByName("debug") {
            storeFile = file("scrollblock.keystore")
            storePassword = "scrollblock"
            keyAlias = "scrollblock"
            keyPassword = "scrollblock"
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
