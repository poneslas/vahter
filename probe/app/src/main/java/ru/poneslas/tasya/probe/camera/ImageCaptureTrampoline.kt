package ru.poneslas.tasya.probe.camera

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import ru.poneslas.tasya.probe.TAG
import java.io.File

/**
 * 6.3: прозрачный батут → системная камера (ACTION_IMAGE_CAPTURE) с EXTRA_OUTPUT через FileProvider.
 * С Android 11 отвечает только предустановленная камера; объявленное и не выданное CAMERA → SecurityException [док].
 */
class ImageCaptureTrampoline : ComponentActivity() {
    private lateinit var file: File
    private var t0 = 0L

    private val take = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val ms = CameraBus.now() - t0
        Log.i(TAG, "IMAGE_CAPTURE result ok=$ok size=${file.length()} за $ms мс")
        if (ok && file.length() > 0) {
            CameraBus.emit(CamEvent.Captured(Variant.SYSTEM, file.path, file.length(), ms))
            finish()
            returnToSheet(file.path, Variant.SYSTEM)
        } else {
            CameraBus.emit(CamEvent.Failed(Variant.SYSTEM, "камера вернула ok=$ok, файл ${file.length()} байт", CameraBus.activityOpen))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CameraBus.emit(CamEvent.ActivityCreated(Variant.SYSTEM, CameraBus.now()))
        setShowWhenLocked(true)
        file = File(attachmentsDir(this), savedInstanceState?.getString("file") ?: "system_${System.currentTimeMillis()}.jpg")
        if (savedInstanceState != null) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        t0 = CameraBus.now()
        try {
            take.launch(uri)
        } catch (t: Throwable) {
            Log.w(TAG, "IMAGE_CAPTURE не запустился", t)
            CameraBus.emit(CamEvent.Failed(Variant.SYSTEM, "${t.javaClass.simpleName}: ${t.message}", CameraBus.activityOpen))
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("file", file.name)
    }
}
