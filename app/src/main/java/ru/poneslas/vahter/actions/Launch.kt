package ru.poneslas.vahter.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import ru.poneslas.vahter.app.MainActivity
import ru.poneslas.vahter.assistant.Sheet
import java.io.File

/**
 * Открыть экран другого приложения. Из листа — поверх листа (лист — экран своей задачи, SheetActivity).
 */
object Launch {
    fun activity(ctx: Context, intent: Intent): Result<Unit> = runCatching {
        // Проверяем заранее: «нет приложения» — ошибка сразу, а не тихо после hide().
        if (intent.resolveActivity(ctx.applicationContext.packageManager) == null && intent.`package` == null) error("no_activity")
        start(ctx, intent)
    }

    /** Из листа — с экрана листа: чужое окно ложится поверх, лист остаётся в своей задаче (свайп — назад к нему). */
    private fun start(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val a = Sheet.activity
        if (Sheet.visible.value && a != null) a.startActivity(intent) else ctx.applicationContext.startActivity(intent)
    }

    /**
     * Открыть файл от мозга системным приложением (FileProvider «…files», кэш hermes/). Без проверки заранее: на content:// с
     * типом resolveActivity видит не все приложения (видимость пакетов Android 11+) — ловим «нет приложения» при запуске.
     */
    fun openFile(ctx: Context, path: String): Result<Unit> = runCatching {
        val f = File(path)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        try { start(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (e: ActivityNotFoundException) { error("no_activity") }
    }

    /** Спросить доступы Android. Из листа — прямо поверх листа; из приложения — приложение. */
    fun askPermissions(ctx: Context, perms: List<String>) {
        val a = Sheet.activity
        val list = perms.joinToString(",")
        if (Sheet.visible.value && a != null) a.askPermissions(perms)
        else ctx.applicationContext.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_ASK_PERM, list))
    }
}
