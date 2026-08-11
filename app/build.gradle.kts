import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localBuildProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun quotedBuildConfigValue(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(character)
        }
    }
    append('"')
}

val offlineCatalogUrl = providers.environmentVariable("SPEED_CAMERA_OFFLINE_CATALOG_URL").orNull
    ?: providers.gradleProperty("speedCameraOfflineCatalogUrl").orNull
    ?: localBuildProperties.getProperty("speedCameraOfflineCatalogUrl")
    ?: ""

val releaseSigningAvailable = listOf(
    "SPEED_CAMERA_KEYSTORE_PATH",
    "SPEED_CAMERA_KEYSTORE_PASSWORD",
    "SPEED_CAMERA_KEY_ALIAS",
    "SPEED_CAMERA_KEY_PASSWORD",
).all { !System.getenv(it).isNullOrBlank() }

android {
    namespace = "com.example.speedcamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.speedcamera"
        minSdk = 29
        targetSdk = 35
        versionCode = System.getenv("SPEED_CAMERA_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("SPEED_CAMERA_VERSION_NAME") ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OFFLINE_CATALOG_URL", quotedBuildConfigValue(offlineCatalogUrl))
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(System.getenv("SPEED_CAMERA_KEYSTORE_PATH"))
                storePassword = System.getenv("SPEED_CAMERA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SPEED_CAMERA_KEY_ALIAS")
                keyPassword = System.getenv("SPEED_CAMERA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    // 1.5.0 keeps the project buildable with the installed stable Android SDK 35.
    val cameraXVersion = "1.5.0"

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-video:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("androidx.camera:camera-effects:$cameraXVersion")

    testImplementation("junit:junit:4.13.2")
}
