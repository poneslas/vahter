package ru.poneslas.tasya.probe.camera

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.poneslas.tasya.probe.assist.OpenRecord
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Варианты П6; значение — аргумент `camera` при открытии листа. */
object Variant {
    const val INSHEET_COMPAT = "insheet"          // 6.1, PreviewView COMPATIBLE (TextureView)
    const val INSHEET_PERF = "insheet_perf"       // 6.1, PreviewView PERFORMANCE (SurfaceView)
    const val ACTIVITY = "activity"               // 6.2
    const val SYSTEM = "system"                   // 6.3

    fun title(v: String) = when (v) {
        INSHEET_COMPAT -> "6.1 на весь экран листа, TextureView"
        INSHEET_PERF -> "6.1 на весь экран листа, SurfaceView"
        ACTIVITY -> "6.2 своя активность"
        SYSTEM -> "6.3 системная камера"
        else -> v
    }
}

sealed class CamEvent {
    abstract val variant: String
    /** Превью пошло (STREAMING). [t] — uptime. */
    data class Streaming(override val variant: String, val t: Long, val open: OpenRecord?, val importance: Int) : CamEvent()
    data class Failed(override val variant: String, val msg: String, val open: OpenRecord?) : CamEvent()
    data class Captured(override val variant: String, val path: String, val bytes: Long, val ms: Long) : CamEvent()
    /** Инструмент на видоискателе заморозил кадр: [ms] — нажатие → готовая картинка. */
    data class Frozen(override val variant: String, val ms: Long, val w: Int, val h: Int) : CamEvent()
    /** 6.2/6.3: активность создана — цена запуска через startAssistantActivity. */
    data class ActivityCreated(override val variant: String, val t: Long) : CamEvent()
}

/** Связка «лист — камера — проверка П6» внутри процесса. */
object CameraBus {
    val events = MutableSharedFlow<CamEvent>(extraBufferCapacity = 64)

    /** Вариант для следующего открытия листа человеком (кнопкой, без аргумента камеры): П6+ «взводит» его. */
    @Volatile var armed: String? = null

    /** Открытие, по которому запущена камера в активности (6.2/6.3). */
    @Volatile var activityOpen: OpenRecord? = null

    /** Живая CameraActivity — авто-серия закрывает её сама. */
    @Volatile var activity: Activity? = null

    fun emit(e: CamEvent) { events.tryEmit(e) }

    fun now() = SystemClock.uptimeMillis()

    fun importance(): Int {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance
    }

    fun importanceName(i: Int) = when (i) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
        else -> i.toString()
    }
}

suspend fun cameraProvider(ctx: Context): ProcessCameraProvider = suspendCancellableCoroutine { c ->
    val f = ProcessCameraProvider.getInstance(ctx)
    f.addListener({
        runCatching { f.get() }.onSuccess { c.resume(it) }.onFailure { c.resumeWithException(it) }
    }, ContextCompat.getMainExecutor(ctx))
}

/** Папка вложений: снимки и копии выбранных файлов. Общая для П6 и П8. */
fun attachmentsDir(ctx: Context) = File(ctx.cacheDir, "attachments").apply { mkdirs() }
