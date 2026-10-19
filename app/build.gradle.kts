import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Распознавание: sherpa-onnx (Apache-2.0). AAR не в git — качается при первой сборке, sha256 закреплён.
val sherpaAar = file("libs/sherpa-onnx-1.13.8.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val tmp = file("libs/sherpa-onnx-1.13.8.aar.part")
    uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar").toURL()
        .openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    val sha = MessageDigest.getInstance("SHA-256").digest(tmp.readBytes()).joinToString("") { "%02x".format(it) }
    check(sha == "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96") { "sherpa-onnx AAR: sha256 не совпал ($sha)" }
    tmp.renameTo(sherpaAar)
}

android {
    namespace = "ru.poneslas.tasya"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "ru.poneslas.tasya"
        minSdk = 34
        // S24 — Android 16 (SDK 36). На 37 поднимем вместе с разрешением на локальную сеть (Архитектура, 18.0).
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" } // распознавание: только arm64 — S24 и эмулятор на Apple Silicon
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val compose = "1.10.1"
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
