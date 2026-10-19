package ru.poneslas.tasya.probe.session

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import ru.poneslas.tasya.probe.TAG
import ru.poneslas.tasya.probe.assist.ProbeSession
import ru.poneslas.tasya.probe.assist.Sheet

/** Событие разблокировки: что ответил requestDismissKeyguard и когда. Времена — uptimeMillis. */
data class UnlockEvent(val result: String, val tAction: Long, val tCreate: Long, val tResult: Long, val wasLocked: Boolean)

object UnlockBus {
    val events = MutableSharedFlow<UnlockEvent>(extraBufferCapacity = 8)
    @Volatile var tAction = 0L

    /** Кнопка листа «Разблокировать»: своя активность поверх блокировки (П5). */
    fun start(session: ProbeSession) {
        tAction = SystemClock.uptimeMillis()
        try {
            session.startAssistantActivity(android.content.Intent(session.context, UnlockActivity::class.java))
            Log.i(TAG, "П5 startAssistantActivity(UnlockActivity) ok")
        } catch (t: Throwable) {
            Log.w(TAG, "П5 startAssistantActivity(UnlockActivity) упал", t)
            events.tryEmit(UnlockEvent("startAssistantActivity: ${t.javaClass.simpleName}: ${t.message}", tAction, 0, SystemClock.uptimeMillis(), false))
        }
    }
}

/**
 * Прозрачная активность: показывается поверх блокировки, просит снять её и после успеха
 * снова открывает лист из сервиса («отложенное действие выполнено»).
 */
class UnlockActivity : Activity() {
    private var tCreate = 0L
    private var locked = false
    private var requested = false
    private var retried = false
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tCreate = SystemClock.uptimeMillis()
        setShowWhenLocked(true)
        val km = getSystemService(KeyguardManager::class.java)
        locked = km.isKeyguardLocked
        Log.i(TAG, "UnlockActivity onCreate locked=$locked secure=${km.isDeviceSecure} через ${tCreate - UnlockBus.tAction} мс")
    }

    /**
     * Просим снять блокировку, когда окно получило фокус: на S24 запрос из onCreate отменялся системой
     * через ~180 мс («cancelled»), человек не успевал приложить палец.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !requested) {
            requested = true
            request()
        }
    }

    private fun request() {
        val tReq = SystemClock.uptimeMillis()
        Log.i(TAG, "UnlockActivity requestDismissKeyguard через ${tReq - UnlockBus.tAction} мс${if (retried) " (повтор)" else ""}")
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done("succeeded")
            override fun onDismissCancelled() {
                // Мгновенная отмена (< 1 с) — не человек; один раз пробуем снова.
                if (!retried && SystemClock.uptimeMillis() - tReq < 1000) {
                    retried = true
                    Log.i(TAG, "UnlockActivity мгновенная отмена — повтор")
                    window.decorView.postDelayed({ request() }, 300)
                } else done(if (retried) "cancelled после повтора" else "cancelled")
            }
            override fun onDismissError() = done("error")
        })
    }

    private fun done(result: String) {
        if (finished) return
        finished = true
        val t = SystemClock.uptimeMillis()
        Log.i(TAG, "UnlockActivity $result через ${t - UnlockBus.tAction} мс от нажатия")
        UnlockBus.events.tryEmit(UnlockEvent(result, UnlockBus.tAction, tCreate, t, locked))
        if (result == "succeeded") {
            Sheet.service?.showSession(Bundle().apply { putString("src", "unlock") }, 0)
        }
        finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }
}
