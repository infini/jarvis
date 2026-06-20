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

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.3.jar"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}
