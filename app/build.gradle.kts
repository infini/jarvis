import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val koreanStreamingAsrModel = mapOf(
    "decoder-epoch-99-avg-1.onnx" to 11_309_084L,
    "encoder-epoch-99-avg-1.int8.onnx" to 126_968_852L,
    "joiner-epoch-99-avg-1.int8.onnx" to 2_581_421L,
    "tokens.txt" to 60_246L,
)
val koreanStreamingAsrBaseUrl =
    "https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16/resolve/main"
val generatedSherpaAssetsDir = layout.buildDirectory.dir("generated/sherpaAssets")
val koreanStreamingAsrDir = generatedSherpaAssetsDir.map { it.dir("sherpa-korean-streaming") }

val downloadKoreanStreamingAsrModel by tasks.registering {
    outputs.dir(koreanStreamingAsrDir)

    doLast {
        val modelDir = koreanStreamingAsrDir.get().asFile
        modelDir.mkdirs()
        modelDir.listFiles()?.forEach { file ->
            if (file.name !in koreanStreamingAsrModel) file.delete()
        }

        koreanStreamingAsrModel.forEach { (fileName, expectedSize) ->
            val target = modelDir.resolve(fileName)
            if (target.isFile && target.length() == expectedSize) return@forEach

            val temp = modelDir.resolve("$fileName.tmp")
            temp.delete()
            val url = "$koreanStreamingAsrBaseUrl/$fileName"
            logger.lifecycle("Downloading $url")
            URI(url).toURL().openStream().use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            require(temp.length() == expectedSize) {
                "Downloaded $fileName has size ${temp.length()}, expected $expectedSize"
            }
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }
    }
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

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedSherpaAssetsDir)
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.3.jar"))
    testImplementation(kotlin("test"))
}

tasks.named("preBuild") {
    dependsOn(downloadKoreanStreamingAsrModel)
}

kotlin {
    jvmToolchain(17)
}
