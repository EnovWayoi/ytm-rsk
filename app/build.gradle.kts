plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.enov.ytm"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.enov.ytm"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.2.2"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    val releaseKeystore = providers.environmentVariable("YTM_KEYSTORE_PATH").orNull
    signingConfigs {
        if (!releaseKeystore.isNullOrBlank()) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = providers.environmentVariable("YTM_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("YTM_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("YTM_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            if (!releaseKeystore.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs.useLegacyPackaging = false }
    lint { abortOnError = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-database:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
