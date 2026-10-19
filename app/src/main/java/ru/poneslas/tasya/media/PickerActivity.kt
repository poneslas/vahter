package ru.poneslas.tasya.media

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.poneslas.tasya.App
import ru.poneslas.tasya.assistant.AssistSession
import ru.poneslas.tasya.assistant.Sheet

/**
 * Скрепка → «Галерея» (проверено на S24, вариант А): окна из листа открываются под листом, поэтому
 * лист сначала прячется, прозрачный батут запускается из процесса помощника, выбор — системный Photo Picker
 * (до 10 фото, разрешение на все фото не нужно), затем лист возвращается с вложениями.
 */
class PickerActivity : ComponentActivity() {
    private val pick = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        val app = App.instance
        app.scope.launch {
            val files = uris.mapNotNull { runCatching { Attachments.fromUri(applicationContext, it) }.getOrNull() }
            finish()
            if (files.isNotEmpty()) app.conversation.attach(files.map { it.path }, if (files.size > 1) "альбом · ${files.size}" else "фото")
            delay(150)
            if (intent.getBooleanExtra("fromSheet", true)) Sheet.show(Bundle().apply { putString("src", "picker") })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    companion object {
        /** Спрятать лист и открыть выбор фото поверх всего. */
        fun launchFromSheet(session: AssistSession) {
            val ctx = session.context.applicationContext
            session.hide()
            App.instance.scope.launch {
                delay(200)
                ctx.startActivity(Intent(ctx, PickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        fun launchFromApp(ctx: Context) =
            ctx.startActivity(Intent(ctx, PickerActivity::class.java).putExtra("fromSheet", false))
    }
}
