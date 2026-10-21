package ru.poneslas.vahter.media

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.poneslas.vahter.App
import ru.poneslas.vahter.assistant.SheetActivity
import ru.poneslas.vahter.assistant.Sheet

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
            if (files.isNotEmpty()) app.conversation.attach(files.map { it.path }, if (files.size > 1) tr(R.string.sheet_via_album, files.size) else tr(R.string.sheet_via_photo))
            delay(150)
            if (intent.getBooleanExtra("fromSheet", true)) Sheet.show(Bundle().apply { putString("src", "picker") })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    companion object {
        /** Выбор фото поверх листа: батут — в задаче листа, лист под ним остаётся и сам получает вложения. */
        fun launchFromSheet(sheet: SheetActivity) =
            sheet.startActivity(Intent(sheet, PickerActivity::class.java).putExtra("fromSheet", false))

        fun launchFromApp(ctx: Context) =
            ctx.startActivity(Intent(ctx, PickerActivity::class.java).putExtra("fromSheet", false))
    }
}
