package ru.poneslas.vahter.media

import ru.poneslas.vahter.assistant.OrbSheet
import ru.poneslas.vahter.assistant.Staged
import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import java.io.File
import ru.poneslas.vahter.App

/**
 * Скрепка → «Галерея»: прозрачный батут открывает системный Photo Picker (до 10 фото, разрешение на все фото не нужно).
 * Из листа батут ложится поверх листа в его задаче (лист — экран, SheetActivity), вложения уходят в разговор, лист остаётся.
 */
class PickerActivity : ComponentActivity() {
    private val pick = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        val app = App.instance
        app.scope.launch {
            val files = uris.mapNotNull { runCatching { Attachments.fromUri(applicationContext, it) }.getOrNull() }
            finish()
            // лист «солнышко»: вложение не уходит само — ждёт над солнышком (смахнуть вверх — сразу, сказать — вместе)
            if (files.isNotEmpty() && stage) { OrbSheet.staged.value = Staged(app.conversation.state.value.mode, files.map { it.path }, if (files.size > 1) tr(R.string.sheet_via_album, files.size) else tr(R.string.sheet_via_photo)); return@launch }
            if (files.isNotEmpty()) app.conversation.attach(files.map { it.path }, if (files.size > 1) tr(R.string.sheet_via_album, files.size) else tr(R.string.sheet_via_photo))
        }
    }

    /** «Файл» (только Hermes): системный выбор документа, копия в кэш — и в разговор (Conversation.sendFile). */
    private val doc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val app = App.instance
        app.scope.launch {
            val got = uri?.let { u -> runCatching {
                val name = contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
                val out = File(Attachments.uploadDir(applicationContext).apply { mkdirs() }, System.currentTimeMillis().toString())
                contentResolver.openInputStream(u)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                out to name
            }.getOrNull() }
            finish()
            if (got != null && stage) { OrbSheet.staged.value = Staged(app.conversation.state.value.mode, emptyList(), "", got.first, got.second); return@launch }
            got?.let { (f, n) -> app.conversation.sendFile(f, n) }
        }
    }

    private val stage get() = intent.getBooleanExtra("stage", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            if (intent.getBooleanExtra("file", false)) doc.launch(arrayOf("*/*"))
            else pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    companion object {
        /** Выбор фото поверх листа или приложения: батут — в их задаче, экран под ним остаётся и сам получает вложения. */
        fun launch(ctx: Context, stage: Boolean = false) =
            ctx.startActivity(Intent(ctx, PickerActivity::class.java).putExtra("stage", stage))

        /** Документ для Hermes — тот же батут поверх листа или приложения. */
        fun launchFile(ctx: Context, stage: Boolean = false) =
            ctx.startActivity(Intent(ctx, PickerActivity::class.java).putExtra("file", true).putExtra("stage", stage))
    }
}
