package ru.poneslas.tasya.assistant

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.SystemClock

/**
 * Разблокировка из листа (проверено на S24): прозрачная активность поверх блокировки просит снять её,
 * когда окно получило фокус (из onCreate система отменяет запрос через ~180 мс), один повтор при мгновенной отмене.
 * После успеха — лист снова (showSession) и выполняет отложенное.
 */
class UnlockActivity : Activity() {
    private var requested = false
    private var retried = false
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !requested) { requested = true; request() }
    }

    private fun request() {
        val tReq = SystemClock.uptimeMillis()
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done(true)
            override fun onDismissCancelled() {
                if (!retried && SystemClock.uptimeMillis() - tReq < 1000) { retried = true; window.decorView.postDelayed({ request() }, 300) }
                else done(false)
            }
            override fun onDismissError() = done(false)
        })
    }

    private fun done(ok: Boolean) {
        if (finished) return
        finished = true
        if (ok) Sheet.show(Bundle().apply { putString("src", "unlock"); intent.getStringExtra("then")?.let { putString("then", it) } })
        finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }
}
