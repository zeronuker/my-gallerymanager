plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from CI secrets; without them the release APK stays unsigned.
val keystore = System.getenv("KEYSTORE_PATH")?.let(::file)?.takeIf { it.exists() }

android {
    // ponytail: placeholder id — changing it later makes Android treat the app as a new one
    namespace = "com.claudeborne.gallerymanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.claudeborne.gallerymanager"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // ponytail: no keystore (local builds) falls back to the debug key so the APK still installs
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.print:print:1.0.0")
    implementation("androidx.biometric:biometric:1.1.0") // app lock
    // ponytail: beta API; the model is fetched by Google Play services the first time it is needed
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    // bundled face detector with contours (works offline), used by portrait retouch
    implementation("com.google.mlkit:face-detection:16.1.7")
    // runs the bundled Real-ESRGAN upscaling model on the CPU
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
