plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is driven entirely by -P properties supplied by release.yml (decoded from
// repository secrets). When they are absent — every local/CI debug build — the release build type
// is simply left unsigned, so nothing here needs the secrets to compile.
val releaseStoreFile = (project.findProperty("PHOTOPULT_STORE_FILE") as String?)
val releaseStorePassword = (project.findProperty("PHOTOPULT_STORE_PASSWORD") as String?)
val releaseKeyAlias = (project.findProperty("PHOTOPULT_KEY_ALIAS") as String?)
val releaseKeyPassword = (project.findProperty("PHOTOPULT_KEY_PASSWORD") as String?)
val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "ru.ui99.photopult"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.ui99.photopult"
        minSdk = 26
        targetSdk = 36

        // Derived from the release tag by release.yml (v1.2.3 → name "1.2.3", code 10203);
        // the fallbacks keep local/debug builds working without any flags.
        versionCode = (project.findProperty("PHOTOPULT_VERSION_CODE") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("PHOTOPULT_VERSION_NAME") as String?) ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Committed debug key so every build (local or CI) shares one signature — debug APKs
        // install as upgrades over each other on test devices instead of erroring with
        // "package conflicts with an existing package". A debug keystore is not a secret.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Core / lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM keeps Compose artifact versions aligned)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // Serialization + coroutines (command/event protocol on the BYTES channel)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // CameraX (used from Stage 3 onward; wired in now so the build stays stable)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Google Nearby Connections (transport, from Stage 2)
    implementation(libs.play.services.nearby)

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
