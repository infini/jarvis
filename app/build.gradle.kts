plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.personal.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.personal.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("ai.picovoice:eagle-android:3.0.2")
    implementation("ai.picovoice:android-voice-processor:1.0.2")
}

kotlin {
    jvmToolchain(17)
}
