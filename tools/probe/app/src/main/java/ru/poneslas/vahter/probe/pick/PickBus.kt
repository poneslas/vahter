package ru.poneslas.vahter.probe.pick

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.poneslas.vahter.probe.App
import ru.poneslas.vahter.probe.TAG
import ru.poneslas.vahter.probe.assist.Sheet
import ru.poneslas.vahter.probe.camera.attachmentsDir
import ru.poneslas.vahter.probe.camera.returnToSheet
import java.io.File

/** Виды выбора П8: photo — 8.1, file — 8.2, embedded — 8.3, share — 8.4. */
sealed class PickEvent {
    abstract val kind: String
    data class TrampolineCreated(override val kind: String, val t: Long) : PickEvent()
    /** Батут ушёл в onPause — пикер встал поверх. */
    data class PickerShown(override val kind: String, val t: Long) : PickEvent()
    data class Picked(override val kind: String, val uri: Uri, val path: String, val mime: String?, val bytes: Long, val copyMs: Long, val tResult: Long) : PickEvent()
    data class Cancelled(override val kind: String, val t: Long) : PickEvent()
    data class Failed(override val kind: String, val msg: String) : PickEvent()
    /** Чтение исходного URI через 5 с после finish() батута. */
    data class LateRead(override val kind: String, val ok: Boolean, val msg: String) : PickEvent()
    /** Как лист вернулся после выбора. */
    data class Returned(override val kind: String, val how: String, val t: Long) : PickEvent()
}

object PickBus {
    val events = MutableSharedFlow<PickEvent>(extraBufferCapacity = 64)
    /** Время нажатия кнопки в листе — отсюда «нажал → пикер виден». */
    @Volatile var tAction = 0L

    fun emit(e: PickEvent) {
        Log.i(TAG, "pick $e")
        events.tryEmit(e)
    }

    fun now() = SystemClock.uptimeMillis()
}

data class Copied(val path: String, val bytes: Long, val mime: String?, val ms: Long)

/** Копия выбранного в cacheDir/attachments — делается до finish() батута, пока права на URI точно есть. */
suspend fun copyToAttachments(ctx: Context, uri: Uri, prefix: String): Copied = withContext(Dispatchers.IO) {
    val t0 = SystemClock.uptimeMillis()
    val cr = ctx.contentResolver
    val name = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "file"
    val safe = name.replace(Regex("[^\\w.\\-]"), "_").takeLast(80)
    val f = File(attachmentsDir(ctx), "${prefix}_${System.currentTimeMillis()}_$safe")
    cr.openInputStream(uri).use { inp ->
        requireNotNull(inp) { "openInputStream вернул null" }
        f.outputStream().use { inp.copyTo(it, 256 * 1024) }
    }
    Copied(f.path, f.length(), cr.getType(uri), SystemClock.uptimeMillis() - t0)
}

/**
 * После finish() батута: вернуть лист с вложением и через 5 с попробовать прочитать исходный URI
 * (права на URI из результата живут, пока жив процесс [док]; проверяем, что так и есть).
 */
fun afterTrampoline(ctx: Context, kind: String, uri: Uri, path: String) {
    App.scope.launch {
        delay(300)
        val wasVisible = Sheet.visible.value
        returnToSheet(path, "pick_$kind")
        PickBus.emit(PickEvent.Returned(kind, if (wasVisible) "лист оставался виден, вложение — через showSession" else "лист был скрыт, вернули showSession", PickBus.now()))
        delay(4700)
        val e = withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.openInputStream(uri).use { it?.read() }
                PickEvent.LateRead(kind, true, "URI читается через 5 с после finish()")
            } catch (s: SecurityException) {
                PickEvent.LateRead(kind, false, "SecurityException: ${s.message}")
            } catch (t: Throwable) {
                PickEvent.LateRead(kind, false, "${t.javaClass.simpleName}: ${t.message}")
            }
        }
        PickBus.emit(e)
    }
}
