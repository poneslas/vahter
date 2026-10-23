// Пробник голосовой клавиатуры: пускает ли клавиатура Samsung (и Gboard) стороннюю голосовую клавиатуру
// на свою кнопку микрофона. Без зависимостей, только Android.
plugins {
    id("com.android.application")
}

android {
    namespace = "ru.poneslas.vahter.imeprobe"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    defaultConfig {
        applicationId = "ru.poneslas.vahter.imeprobe"
        minSdk = 34
        targetSdk = 37
        versionCode = 5
        versionName = "0.5"
    }
    buildTypes {
        debug { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
