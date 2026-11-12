import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.net.URI

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.voldemar64.healthapp.speech"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        lintConfig = rootProject.file("lint.xml")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

val modelHash = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"
val speechAssets = layout.buildDirectory.dir("generated/speechAssets")
val prepareSpeechModel by tasks.registering {
    inputs.property("modelSha256", modelHash)
    outputs.dir(speechAssets)
    doLast {
        fun hash(file: File) = MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { stream ->
                val bytes = ByteArray(65536)
                while (true) { val count = stream.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val archive = rootProject.file("../../.cache/speech/vosk.zip")
        archive.parentFile.mkdirs()
        if (!archive.exists()) {
            val temporary = File(archive.parentFile, "vosk.download")
            val connection = URI("https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip").toURL().openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            connection.getInputStream().use { source -> temporary.outputStream().use { source.copyTo(it) } }
            check(hash(temporary) == modelHash) { "Speech model checksum mismatch" }
            check(temporary.renameTo(archive)) { "Cannot finalize model download" }
        }
        check(hash(archive) == modelHash) { "Speech model checksum mismatch; remove .cache/speech/vosk.zip and retry" }
        val root = speechAssets.get().asFile
        root.deleteRecursively()
        val model = File(root, "vosk").apply { mkdirs() }
        var expanded = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                check(entry.name.startsWith("vosk-model-small-ru-0.22/")) { "Unexpected archive root" }
                val name = entry.name.removePrefix("vosk-model-small-ru-0.22/")
                val destination = File(model, name).canonicalFile
                check(destination.toPath().startsWith(model.canonicalFile.toPath())) { "Unsafe archive path" }
                if (entry.isDirectory) destination.mkdirs() else {
                    destination.parentFile.mkdirs()
                    destination.outputStream().use { output ->
                        val bytes = ByteArray(65536)
                        while (true) {
                            val count = zip.read(bytes)
                            if (count < 0) break
                            expanded += count
                            check(expanded <= 200_000_000) { "Model archive exceeds size limit" }
                            output.write(bytes, 0, count)
                        }
                    }
                }
            }
        }
        check(File(model, "am/final.mdl").isFile && File(model, "conf/model.conf").isFile)
        File(root, "vosk-manifest.txt").writeText(model.walkTopDown().filter { it.isFile }.sortedBy { it.path }
            .joinToString("\n") { "${hash(it)}  ${it.relativeTo(model).invariantSeparatorsPath}" })
    }
}
android.sourceSets["main"].assets.srcDir(speechAssets)
tasks.named("preBuild") { dependsOn(prepareSpeechModel) }

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.coroutines.core)
    implementation(libs.vosk.android) { artifact { type = "aar" } }
    implementation(libs.jna) { artifact { type = "aar" } }
    testImplementation(libs.junit)
}
