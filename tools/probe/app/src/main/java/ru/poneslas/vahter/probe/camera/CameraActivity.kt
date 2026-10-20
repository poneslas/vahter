package ru.poneslas.vahter.probe.camera

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import ru.poneslas.vahter.probe.App
import ru.poneslas.vahter.probe.TAG
import ru.poneslas.vahter.probe.assist.Sheet
import java.io.File

/**
 * 6.2: своя активность-камера, запускается из листа через startAssistantActivity.
 * Снимок → finish() → showSession с вложением. Видна поверх блокировки.
 */
class CameraActivity : ComponentActivity() {
    private val variant = Variant.ACTIVITY
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    private var provider: ProcessCameraProvider? = null
    private var streamed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CameraBus.emit(CamEvent.ActivityCreated(variant, CameraBus.now()))
        CameraBus.activity = this
        setShowWhenLocked(true)
        Log.i(TAG, "CameraActivity onCreate")

        previewView = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.PERFORMANCE }
        status = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); setPadding(24, 24, 24, 24) }
        val shoot = Button(this).apply { text = "Снять"; setOnClickListener { shoot() } }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(previewView)
            addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            addView(shoot, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 120 })
        })

        val open = CameraBus.activityOpen
        previewView.previewStreamState.observe(this) { st ->
            if (st == PreviewView.StreamState.STREAMING && !streamed) {
                streamed = true
                val t = CameraBus.now()
                val imp = CameraBus.importance()
                status.text = "видоискатель: ${open?.let { t - (it.tCall ?: it.tPrepare ?: it.tShow) }?.let { "$it мс от вызова" } ?: "идёт"}"
                CameraBus.emit(CamEvent.Streaming(variant, t, open, imp))
            }
        }
        lifecycleScope().launch {
            try {
                val p = cameraProvider(this@CameraActivity)
                provider = p
                val selector = if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
                else CameraSelector.DEFAULT_FRONT_CAMERA
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                p.unbindAll()
                val cam = p.bindToLifecycle(this@CameraActivity, selector, preview, capture)
                cam.cameraInfo.cameraState.observe(this@CameraActivity) { cs ->
                    cs.error?.let { e ->
                        val msg = "CameraState ${cs.type} error=${e.code}"
                        status.text = "ошибка: $msg"
                        CameraBus.emit(CamEvent.Failed(variant, msg, open))
                    }
                }
            } catch (t: Throwable) {
                status.text = "ошибка: ${t.javaClass.simpleName}: ${t.message}"
                CameraBus.emit(CamEvent.Failed(variant, "${t.javaClass.simpleName}: ${t.message}", open))
            }
        }
    }

    private fun lifecycleScope() = App.scope

    private fun shoot() {
        val f = File(attachmentsDir(this), "activity_${System.currentTimeMillis()}.jpg")
        val t0 = CameraBus.now()
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(f).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    CameraBus.emit(CamEvent.Captured(variant, f.path, f.length(), CameraBus.now() - t0))
                    finish()
                    returnToSheet(f.path, variant)
                }

                override fun onError(e: ImageCaptureException) {
                    status.text = "снимок не удался: ${e.message}"
                    CameraBus.emit(CamEvent.Failed(variant, "takePicture ${e.imageCaptureError}: ${e.message}", CameraBus.activityOpen))
                }
            },
        )
    }

    override fun onDestroy() {
        if (CameraBus.activity === this) CameraBus.activity = null
        Log.i(TAG, "CameraActivity onDestroy")
        super.onDestroy()
    }
}

/** Вернуть лист с вложением: сессия получает args `attachment` и `src`. */
fun returnToSheet(path: String, src: String) {
    App.scope.launch {
        val svc = Sheet.awaitService() ?: return@launch
        svc.showSession(Bundle().apply { putString("attachment", path); putString("src", src) }, 0)
    }
}
