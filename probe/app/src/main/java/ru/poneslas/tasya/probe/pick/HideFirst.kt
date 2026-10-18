package ru.poneslas.tasya.probe.pick

import android.Manifest
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.delay
import ru.poneslas.tasya.probe.App
import ru.poneslas.tasya.probe.TAG
import ru.poneslas.tasya.probe.assist.ProbeSession
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.assist.SheetAction
import ru.poneslas.tasya.probe.perm.PermTrampolineActivity

/**
 * Обход «окно выбора под листом» (S24, 18.10): окно помощника лежит над приложениями, поэтому
 * пикеры и окна разрешений, запущенные из листа, открываются под ним. Здесь лист сначала прячется,
 * батут запускается обычным startActivity из процесса помощника, после выбора лист возвращается.
 * Разрешит ли система запуск активности из фона после hide() — [?], ради этого и кнопки.
 */
object HideFirst {
    val actions = listOf(
        SheetAction("hide", "Фото — спрятав лист") { s -> launch(s, Intent(s.context, PickerTrampolineActivity::class.java).putExtra("kind", "photo")) },
        SheetAction("hide", "Файл — спрятав лист") { s -> launch(s, Intent(s.context, PickerTrampolineActivity::class.java).putExtra("kind", "file")) },
        SheetAction("hide", "Микрофон — спрятав лист") { s ->
            launch(s, Intent(s.context, PermTrampolineActivity::class.java)
                .putExtra(PermTrampolineActivity.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.RECORD_AUDIO)))
            reopenWhenPermissionDone(s.context)
        },
    )

    internal suspend fun launch(s: ProbeSession, i: Intent) {
        PickBus.tAction = PickBus.now()
        val ctx = s.context.applicationContext
        s.hide()
        delay(200)
        val err = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.exceptionOrNull()
        Log.i(TAG, "hide-first ${i.component?.shortClassName}: ${err?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "запущено"}")
        if (err != null) Sheet.setStatus("спрятав лист: не запустилось — ${err.javaClass.simpleName}")
    }

    /** У батута разрешений нет своего возврата в лист — ждём, пока он закроется, и открываем лист. */
    private suspend fun reopenWhenPermissionDone(ctx: Context) {
        val t0 = System.currentTimeMillis()
        var seen = false
        while (System.currentTimeMillis() - t0 < 120_000) {
            val top = PermTrampolineActivity.alive
            if (top) seen = true
            if (seen && !top) break
            delay(200)
        }
        val granted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        Sheet.awaitService()?.showSession(android.os.Bundle().apply { putString("src", "perm_hide_first") }, 0)
        Sheet.setStatus("микрофон: ${if (granted) "выдан" else "не выдан"}${if (!seen) " (окно не появилось)" else ""}")
        Log.i(TAG, "hide-first микрофон: granted=$granted, окно было=$seen")
    }
}
