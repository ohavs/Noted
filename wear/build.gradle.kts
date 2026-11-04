plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The watch app ships next to the phone app in each release (noted-watch-<v>.apk). Same package +
// same signing key as the phone: the Wearable Data Layer only talks between those.
val vCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val vName = "0.2.$vCode"

android {
    namespace = "com.ohav.noted.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ohav.noted"
        minSdk = 30
        targetSdk = 36
        versionCode = vCode
        versionName = vName
        buildConfigField("String", "UPDATE_REPO", "\"ohavs/Noted\"")
    }

    // github: the APK on GitHub Releases, updates itself ("בדוק עדכונים"). play: the Google Play
    // build — Play forbids self-updating apps, so no updater and no REQUEST_INSTALL_PACKAGES.
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            isDefault = true
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("NOTED_KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("NOTED_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NOTED_KEY_ALIAS") ?: "noted"
                keyPassword = System.getenv("NOTED_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("NOTED_KEYSTORE_FILE") != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.remote)
    implementation(libs.concurrent.futures)
    implementation(libs.play.wearable)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play)
}
