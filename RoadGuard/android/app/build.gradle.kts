import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

// Load local configuration from local.properties
// In CI, inject these as environment variables instead.
val localProps = Properties()
val localFile = rootProject.file("local.properties")
if (localFile.exists()) {
    localProps.load(localFile.inputStream())
}

fun localProp(key: String, envFallback: String = ""): String =
    localProps.getProperty(key) ?: System.getenv(key) ?: envFallback

android {
    namespace         = "com.roadguard.app"
    compileSdk        = 35

    defaultConfig {
        applicationId = "com.roadguard.app"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = 1
        versionName   = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject Supabase config from local.properties or environment
        // These are the PUBLIC/ANON keys — safe for client use with proper RLS.
        buildConfigField("String", "SUPABASE_URL",
            "\"${localProp("SUPABASE_URL", "https://your-project-ref.supabase.co")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY",
            "\"${localProp("SUPABASE_ANON_KEY", "your-anon-key-here")}\"")
        buildConfigField("Boolean", "DEMO_MODE",
            localProp("ROADGUARD_DEMO_MODE", "true"))

        // BLE configuration (must match firmware constants)
        buildConfigField("String", "BLE_SERVICE_UUID",
            "\"6E400001-B5A3-F393-E0A9-E50E24DCCA9E\"")
        buildConfigField("String", "BLE_CHAR_UUID_TX",
            "\"6E400003-B5A3-F393-E0A9-E50E24DCCA9E\"")
        buildConfigField("String", "BLE_CHAR_UUID_RX",
            "\"6E400002-B5A3-F393-E0A9-E50E24DCCA9E\"")
        buildConfigField("String", "BLE_DEVICE_NAME_PREFIX",
            "\"RoadGuard-ESP32\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            buildConfigField("Boolean", "DEMO_MODE",
                localProp("ROADGUARD_DEMO_MODE", "true"))
        }
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("Boolean", "DEMO_MODE", "false")
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
        compose    = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Core AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose BOM — ensures all Compose libraries are version-compatible
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Hilt dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Supabase SDK
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.auth)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.storage)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.core)

    // Room (local hazard cache)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore (settings persistence)
    implementation(libs.datastore.preferences)

    // OSMDroid (OpenStreetMap for hazard map)
    implementation(libs.osmdroid.android)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Unit Tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.arch.core.testing)
    testImplementation(libs.room.testing)

    // Instrumented Tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
