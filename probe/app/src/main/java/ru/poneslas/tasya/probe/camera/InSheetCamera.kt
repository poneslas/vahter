package ru.poneslas.tasya.probe.camera

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import ru.poneslas.tasya.probe.TAG
import ru.poneslas.tasya.probe.assist.OpenRecord
import ru.poneslas.tasya.probe.assist.ProbeSession
import ru.poneslas.tasya.probe.assist.Sheet
import java.io.File

/** 6.1: CameraX прямо в окне листа, привязан к жизненному циклу сессии. */
object InSheetCamera {
    @Volatile var active = false
        private set

    fun start(variant: String, open: OpenRecord?) {
        active = true
        Sheet.setPanel { s -> CameraPanel(s, variant, open) }
    }

    fun stop() {
        if (!active) return
        active = false
        Sheet.setPanel(null)
    }
}

@Composable
private fun CameraPanel(session: ProbeSession, variant: String, open: OpenRecord?) {
    val ctx = LocalContext.current
    val previewView = remember(variant) {
        PreviewView(ctx).apply {
            implementationMode = if (variant == Variant.INSHEET_PERF) PreviewView.ImplementationMode.PERFORMANCE
            else PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val capture = remember(variant) { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val camera = remember(variant) { arrayOfNulls<androidx.camera.core.Camera>(1) }
    var state by remember { mutableStateOf("камера: запуск (${Variant.title(variant)})") }
    var shot by remember { mutableStateOf<String?>(null) }

    val streamObserver = remember(variant) {
        var done = false
        Observer<PreviewView.StreamState> { st ->
            if (st == PreviewView.StreamState.STREAMING && !done) {
                done = true
                val t = CameraBus.now()
                val imp = CameraBus.importance()
                state = "видоискатель: ${open?.let { t - (it.tCall ?: it.tPrepare ?: it.tShow) }?.let { "$it мс от вызова" } ?: "идёт"}"
                Log.i(TAG, "camera $variant STREAMING importance=${CameraBus.importanceName(imp)}")
                CameraBus.emit(CamEvent.Streaming(variant, t, open, imp))
            }
        }
    }
    val camStateObserver = remember(variant) {
        Observer<CameraState> { cs ->
            Log.i(TAG, "camera $variant state=${cs.type} error=${cs.error?.code}")
            cs.error?.let { e ->
                val msg = "CameraState ${cs.type} error=${e.code}" + (e.cause?.let { " ${it.javaClass.simpleName}: ${it.message}" } ?: "")
                state = "ошибка: $msg"
                CameraBus.emit(CamEvent.Failed(variant, msg, open))
            }
        }
    }

    LaunchedEffect(variant) {
        try {
            val p = cameraProvider(ctx)
            provider = p
            val selector = if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
            else CameraSelector.DEFAULT_FRONT_CAMERA
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            p.unbindAll()
            val cam = p.bindToLifecycle(session, selector, preview, capture)
            camera[0] = cam
            cam.cameraInfo.cameraState.observeForever(camStateObserver)
            previewView.previewStreamState.observeForever(streamObserver)
        } catch (t: Throwable) {
            Log.w(TAG, "camera $variant bind failed", t)
            state = "ошибка: ${t.javaClass.simpleName}: ${t.message}"
            CameraBus.emit(CamEvent.Failed(variant, "${t.javaClass.simpleName}: ${t.message}", open))
        }
    }
    DisposableEffect(variant) {
        onDispose {
            previewView.previewStreamState.removeObserver(streamObserver)
            camera[0]?.cameraInfo?.cameraState?.removeObserver(camStateObserver)
            runCatching { provider?.unbindAll() }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AndroidView({ previewView }, Modifier.fillMaxWidth().height(260.dp))
        Text(state, fontSize = 12.sp)
        Button(onClick = {
            val f = File(attachmentsDir(ctx), "insheet_${System.currentTimeMillis()}.jpg")
            val t0 = CameraBus.now()
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(f).build(),
                ContextCompat.getMainExecutor(ctx),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val ms = CameraBus.now() - t0
                        shot = f.path
                        state = "снято за $ms мс"
                        CameraBus.emit(CamEvent.Captured(variant, f.path, f.length(), ms))
                    }

                    override fun onError(e: ImageCaptureException) {
                        state = "снимок не удался: ${e.imageCaptureError} ${e.message}"
                        CameraBus.emit(CamEvent.Failed(variant, "takePicture ${e.imageCaptureError}: ${e.message}", open))
                    }
                },
            )
        }) { Text("Снять") }
        shot?.let { AttachmentThumb(it) }
    }
}
