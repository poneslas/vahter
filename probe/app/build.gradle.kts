import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ./gradlew assembleDebug -Pbump=1 — та же сборка с versionCode+1 (П1, П14)
// -PnoRecognizer — XML помощника без recognitionService (контрольный опыт П1)
val bump = (findProperty("bump") as String?)?.toInt() ?: 0
val noRecognizer = hasProperty("noRecognizer")

// asr: sherpa-onnx (П15). AAR не в репозитории — качается при первой сборке, sha256 закреплён.
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
    namespace = "ru.poneslas.tasya.probe"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "ru.poneslas.tasya.probe"
        minSdk = 34
        targetSdk = 37
        versionCode = 1 + bump
        versionName = "0.1.${1 + bump}" + if (noRecognizer) "-norec" else ""
        manifestPlaceholders["visXml"] =
            if (noRecognizer) "@xml/interaction_service_norec" else "@xml/interaction_service"
        ndk { abiFilters += "arm64-v8a" } // asr: только arm64 — S24 и эмулятор на M4
    }

    buildTypes {
        debug { isMinifyEnabled = false }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val compose = "1.10.1"
    val camera = "1.4.1"
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.camera:camera-core:$camera")
    implementation("androidx.camera:camera-camera2:$camera")
    implementation("androidx.camera:camera-lifecycle:$camera")
    implementation("androidx.camera:camera-view:$camera")
    implementation("androidx.work:work-runtime:2.10.1")
    implementation("org.unifiedpush.android:connector:3.3.5") // push
    implementation("androidx.photopicker:photopicker-compose:1.0.0-alpha01") // pick
    implementation(files("libs/sherpa-onnx-1.13.8.aar")) // asr
}
