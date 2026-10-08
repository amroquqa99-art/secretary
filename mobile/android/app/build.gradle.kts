import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.alsekretary.app"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.alsekretary.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.9.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                System.getenv("SECRETARY_LLAMA_CPP_DIR")?.let { arguments += "-DLLAMA_CPP_DIR=$it" }
            }
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1536m"
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            System.getProperty("https.proxyHost")?.let { host ->
                it.systemProperty("robolectric.dependency.proxy.host", host)
                it.systemProperty("robolectric.dependency.proxy.port", System.getProperty("https.proxyPort", "443"))
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    androidResources { noCompress += "zip" }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

abstract class PrepareSpeechAssets : DefaultTask() {
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
    @get:Input val pinnedModels = listOf(
        "vosk-model-small-en-us-0.15|41205931|30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
        "vosk-model-ar-mgb2-0.4|333241610|357469ae1bb4d7a3810c9cd6b86d33bc135898dfc134e6df8bc2ddd28c5fe77a"
    )
    @TaskAction fun prepare() {
        val output = assetsDirectory.get().asFile.resolve("speech").apply { mkdirs() }
        val cache = project.file(System.getenv("SECRETARY_SPEECH_CACHE_DIR") ?: "${project.rootDir}/.speech-model-cache").apply { mkdirs() }
        fun hash(file: File) = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) }
            digest.digest().joinToString("") { "%02x".format(Locale.US,it) }
        }
        for (pin in pinnedModels) {
            val (name,size,digest) = pin.split('|')
            val archive = cache.resolve("$name.zip")
            if (!archive.isFile || archive.length()!=size.toLong() || hash(archive)!=digest) {
                val partial = cache.resolve("$name.zip.part")
                try {
                    val connection = URI.create("https://alphacephei.com/vosk/models/$name.zip").toURL().openConnection().apply { connectTimeout=30000;readTimeout=60000 }
                    connection.getInputStream().use { input -> partial.outputStream().use { out ->
                        var total=0L;val buffer=ByteArray(65536)
                        while(true) { val n=input.read(buffer);if(n<0)break;total+=n;check(total<=size.toLong());out.write(buffer,0,n) }
                    } }
                    check(partial.length()==size.toLong() && hash(partial)==digest) { "Speech asset checksum mismatch: $name" }
                    Files.move(partial.toPath(),archive.toPath(),StandardCopyOption.REPLACE_EXISTING)
                } finally { partial.delete() }
            }
            archive.copyTo(output.resolve("$name.zip"),overwrite=true)
        }
    }
}
val prepareSpeechAssets = tasks.register<PrepareSpeechAssets>("prepareSpeechAssets") {
    assetsDirectory.set(layout.buildDirectory.dir("generated/speech-assets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareSpeechAssets, PrepareSpeechAssets::assetsDirectory)
}
