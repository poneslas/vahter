package ru.poneslas.vahter.probe.pick

import android.os.Build
import android.os.ext.SdkExtensions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.photopicker.compose.EmbeddedPhotoPicker
import androidx.photopicker.compose.ExperimentalPhotoPickerComposeApi
import androidx.photopicker.compose.rememberEmbeddedPhotoPickerState
import kotlinx.coroutines.launch
import ru.poneslas.vahter.probe.App
import ru.poneslas.vahter.probe.camera.AttachmentThumb

/** 8.3 доступен: Android 14+ с SDK Extensions ≥ 15 [док]. */
fun embeddedPickerAvailable(): Boolean = runCatching {
    SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 15
}.getOrDefault(false)

/**
 * 8.3: встроенный Photo Picker прямо в окне листа (androidx.photopicker 1.0.0-alpha02, SurfaceView).
 * Пример в документации — в активности; работает ли в окне TYPE_VOICE_INTERACTION — это и проверяем [?].
 */
@OptIn(ExperimentalPhotoPickerComposeApi::class)
@Composable
fun EmbeddedPickerPanel() {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("встроенный пикер: загрузка") }
    var shot by remember { mutableStateOf<String?>(null) }
    if (!embeddedPickerAvailable()) {
        Text("Встроенный пикер недоступен: нужен SDK Extensions ≥ 15", fontSize = 12.sp)
        return
    }
    val state = rememberEmbeddedPhotoPickerState(
        onSessionError = { t ->
            status = "ошибка сессии: ${t.javaClass.simpleName}: ${t.message}"
            PickBus.emit(PickEvent.Failed("embedded", status))
        },
        onUriPermissionGranted = { uris ->
            val tResult = PickBus.now()
            uris.firstOrNull()?.let { uri ->
                App.scope.launch {
                    try {
                        val c = copyToAttachments(ctx.applicationContext, uri, "embedded")
                        shot = c.path
                        status = "выбрано: ${c.bytes / 1024} КБ, копия ${c.ms} мс"
                        PickBus.emit(PickEvent.Picked("embedded", uri, c.path, c.mime, c.bytes, c.ms, tResult))
                    } catch (t: Throwable) {
                        PickBus.emit(PickEvent.Failed("embedded", "копия: ${t.javaClass.simpleName}: ${t.message}"))
                    }
                }
            }
        },
        onUriPermissionRevoked = { uris -> PickBus.emit(PickEvent.Failed("embedded", "права отозваны: ${uris.size}")) },
        onSelectionComplete = { status = "выбор завершён" },
    )
    Column {
        EmbeddedPhotoPicker(state = state, modifier = Modifier.fillMaxWidth().height(320.dp))
        Text(status, fontSize = 12.sp)
        shot?.let { AttachmentThumb(it, "встроенный пикер") }
    }
}
