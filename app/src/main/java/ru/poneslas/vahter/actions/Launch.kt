package ru.poneslas.vahter.actions

import android.content.Context
import android.content.Intent
import ru.poneslas.vahter.app.MainActivity
import ru.poneslas.vahter.assistant.Sheet

/**
 * Открыть экран другого приложения. Из листа — поверх листа (лист — экран своей задачи, SheetActivity).
 */
object Launch {
    fun activity(ctx: Context, intent: Intent): Result<Unit> = runCatching {
        val app = ctx.applicationContext
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Проверяем заранее: «нет приложения» — ошибка сразу, а не тихо после hide().
        if (intent.resolveActivity(app.packageManager) == null && intent.`package` == null) error("no_activity")
        // Из листа — с экрана листа: чужое окно ложится поверх, лист остаётся в своей задаче (свайп — назад к нему).
        val a = Sheet.activity
        if (Sheet.visible.value && a != null) a.startActivity(intent) else app.startActivity(intent)
    }

    /**
     * Спросить доступы Android. Из листа — прямо поверх листа; из приложения — приложение.
     */
    /** Открыть файл от мозга системным приложением (FileProvider «…files», кэш hermes/). */
    fun openFile(ctx: Context, path: String): Result<Unit> = runCatching {
        val f = java.io.File(path)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        activity(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)).getOrThrow()
    }

    fun askPermissions(ctx: Context, perms: List<String>) {
        val a = Sheet.activity
        val list = perms.joinToString(",")
        if (Sheet.visible.value && a != null) a.askPermissions(perms)
        else ctx.applicationContext.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_ASK_PERM, list))
    }
}
