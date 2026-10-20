package ru.poneslas.vahter.probe.pick

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import ru.poneslas.vahter.probe.App
import ru.poneslas.vahter.probe.TAG

/**
 * 8.1/8.2: у сессии нет startActivityForResult, поэтому лист запускает прозрачный батут через
 * startAssistantActivity, а батут — Photo Picker или OpenDocument. Копия файла — до finish().
 */
class PickerTrampolineActivity : ComponentActivity() {
    private val kind get() = intent.getStringExtra("kind") ?: "photo"
    private var launched = false
    private var shownReported = false

    private val photo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { handle(it) }
    private val doc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { handle(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PickBus.emit(PickEvent.TrampolineCreated(kind, PickBus.now()))
        Log.i(TAG, "PickerTrampoline onCreate kind=$kind")
        if (savedInstanceState != null) { launched = true; return }
        try {
            if (kind == "file") doc.launch(arrayOf("*/*"))
            else photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            launched = true
        } catch (t: Throwable) {
            PickBus.emit(PickEvent.Failed(kind, "запуск пикера: ${t.javaClass.simpleName}: ${t.message}"))
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (launched && !shownReported) {
            shownReported = true
            PickBus.emit(PickEvent.PickerShown(kind, PickBus.now()))
        }
    }

    private fun handle(uri: Uri?) {
        val tResult = PickBus.now()
        if (uri == null) {
            PickBus.emit(PickEvent.Cancelled(kind, tResult))
            finish()
            return
        }
        val k = kind
        App.scope.launch {
            try {
                val c = copyToAttachments(applicationContext, uri, k)
                PickBus.emit(PickEvent.Picked(k, uri, c.path, c.mime, c.bytes, c.ms, tResult))
                finish()
                afterTrampoline(applicationContext, k, uri, c.path)
            } catch (t: Throwable) {
                PickBus.emit(PickEvent.Failed(k, "копия: ${t.javaClass.simpleName}: ${t.message}"))
                finish()
            }
        }
    }
}
