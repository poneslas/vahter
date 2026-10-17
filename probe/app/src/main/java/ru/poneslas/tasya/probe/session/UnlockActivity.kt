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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tCreate = SystemClock.uptimeMillis()
        setShowWhenLocked(true)
        val km = getSystemService(KeyguardManager::class.java)
        val locked = km.isKeyguardLocked
        Log.i(TAG, "UnlockActivity onCreate locked=$locked secure=${km.isDeviceSecure} через ${tCreate - UnlockBus.tAction} мс")
        fun done(result: String) {
            val t = SystemClock.uptimeMillis()
            Log.i(TAG, "UnlockActivity $result через ${t - UnlockBus.tAction} мс от нажатия")
            UnlockBus.events.tryEmit(UnlockEvent(result, UnlockBus.tAction, tCreate, t, locked))
            if (result == "succeeded") {
                Sheet.service?.showSession(Bundle().apply { putString("src", "unlock") }, 0)
            }
            finish()
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        // [?] если экран не заблокирован, ожидаем немедленный onDismissSucceeded
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done("succeeded")
            override fun onDismissCancelled() = done("cancelled")
            override fun onDismissError() = done("error")
        })
    }
}
