plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// CI passes -PversionCode=<run number>; versionName = "<major>.<minor>.<code>" so the release tag sorts with it.
val vCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val vName = "0.2.$vCode"

android {
    namespace = "com.ohav.noted"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ohav.noted"
        minSdk = 26
        targetSdk = 36
        versionCode = vCode
        versionName = vName
        buildConfigField("String", "UPDATE_REPO", "\"ohavs/Noted\"")
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
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
}
