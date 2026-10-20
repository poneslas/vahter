package ru.poneslas.vahter.probe.entry

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.launch
import ru.poneslas.vahter.probe.App

/**
 * Прозрачный батут без анимации: открывает лист и сразу закрывается.
 * extras: via=service|showAssist, src, mode, camera, collapse, t_entry (если вход начался раньше — плитка).
 */
open class EntryTrampoline(private val defaultSrcPrefix: String) : Activity() {
    private var tEntry = 0L
    private var src = ""
    private var pendingAssist = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        tEntry = intent.getLongExtra("t_entry", 0L).takeIf { it > 0 } ?: SystemClock.uptimeMillis()
        val via = intent.getStringExtra("via") ?: "service"
        src = intent.getStringExtra("src") ?: "${defaultSrcPrefix}_$via"
        if (via == "showAssist") {
            pendingAssist = true
        } else {
            val args = EntryLauncher.argsFrom(intent.extras)
            App.scope.launch {
                EntryLauncher.viaService(src, args, tEntry)
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!pendingAssist) return
        pendingAssist = false
        // showAssist проверяет, что активность наверху, — зовём после того, как окно получило кадр.
        window.decorView.post {
            EntryLauncher.viaShowAssist(this, src, EntryLauncher.argsFrom(intent.extras), tEntry)
            finish()
        }
    }
}
