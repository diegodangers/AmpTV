import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "io.aviaterra.amptv"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.aviaterra.amptv"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        versionName = "0.1.0"
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"243b60c04a5446acaae2693cf4aee759\"")
    }
    signingConfigs {
        create("persistent") {
            val path = System.getenv("AMPTV_SIGNING_STORE_FILE")
            if (!path.isNullOrBlank()) {
                storeFile = File(path)
                storePassword = System.getenv("AMPTV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AMPTV_KEY_ALIAS")
                keyPassword = System.getenv("AMPTV_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (!System.getenv("AMPTV_SIGNING_STORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("persistent")
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}