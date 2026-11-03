plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// CI writes app/google-services.json from the Firebase project (see .github/workflows/android.yml).
// Without it the app builds and runs local-only; sign-in says it isn't available.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

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
        // Maps SDK key (Places search goes through the server): a GitHub secret in CI, local.properties or -P locally; empty = radar + platform geocoder
        val mapsKey = System.getenv("MAPS_API_KEY")?.trim().orEmpty().ifEmpty { (project.findProperty("MAPS_API_KEY") as String?).orEmpty() }
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsKey\"")
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
    implementation(libs.mlkit.docscanner)
    implementation(libs.play.location)
    implementation(libs.maps.compose)
    implementation(libs.coroutines.play)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.functions)
    implementation(libs.credentials)
    implementation(libs.credentials.play)
    implementation(libs.googleid)
    implementation(libs.glance.appwidget)
    implementation(libs.play.wearable)
    testImplementation(libs.junit)
}
