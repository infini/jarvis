import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

data class ModelAsset(val size: Long, val sha256: String)

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

val koreanStreamingAsrModel = mapOf(
    "decoder-epoch-99-avg-1.onnx" to ModelAsset(
        11_309_084L,
        "b29cfb4575141e50a30a22b2c4579934f3d4f45b83c9c8c08c3aef5a3fa7abfc",
    ),
    "encoder-epoch-99-avg-1.int8.onnx" to ModelAsset(
        126_968_852L,
        "8d0b1aa24fbedd4e3948564ab7facd151b8ce9b0c48fc987c541de2de3af5697",
    ),
    "joiner-epoch-99-avg-1.int8.onnx" to ModelAsset(
        2_581_421L,
        "128b80a66a1f718488af8560f9d15895109b99ff3e573f0a0130e03774ef1ced",
    ),
    "tokens.txt" to ModelAsset(
        60_246L,
        "016bdf0965029263b7ad01b742366ee542ef0bef38261510e8176ff6f2e9e668",
    ),
)
val koreanStreamingAsrBaseUrl =
    "https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16/resolve/main"
val generatedSherpaAssetsDir = layout.buildDirectory.dir("generated/sherpaAssets")
val koreanStreamingAsrDir = generatedSherpaAssetsDir.map { it.dir("sherpa-korean-streaming") }

val localKeystoreProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use(::load)
}

fun signingValue(name: String): String? = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orNull
    ?: localKeystoreProperties.getProperty(name)

val releaseStoreFile = signingValue("JARVIS_RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("JARVIS_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("JARVIS_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("JARVIS_RELEASE_KEY_PASSWORD")
val releaseSigningReady = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

val downloadKoreanStreamingAsrModel by tasks.registering {
    outputs.dir(koreanStreamingAsrDir)

    doLast {
        val modelDir = koreanStreamingAsrDir.get().asFile
        modelDir.mkdirs()
        modelDir.listFiles()?.forEach { file ->
            if (file.name !in koreanStreamingAsrModel) file.delete()
        }

        koreanStreamingAsrModel.forEach { (fileName, expected) ->
            val target = modelDir.resolve(fileName)
            if (target.isFile && target.length() == expected.size && target.sha256() == expected.sha256) {
                return@forEach
            }

            val temp = modelDir.resolve("$fileName.tmp")
            temp.delete()
            val url = "$koreanStreamingAsrBaseUrl/$fileName"
            logger.lifecycle("Downloading $url")
            URI(url).toURL().openStream().use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            require(temp.length() == expected.size) {
                "Downloaded $fileName has size ${temp.length()}, expected ${expected.size}"
            }
            val actualSha256 = temp.sha256()
            require(actualSha256 == expected.sha256) {
                "Downloaded $fileName has SHA-256 $actualSha256, expected ${expected.sha256}"
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
        versionCode = 2
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedSherpaAssetsDir)
        }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
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
