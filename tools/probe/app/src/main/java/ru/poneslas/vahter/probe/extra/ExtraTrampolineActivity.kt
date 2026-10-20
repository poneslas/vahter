package ru.poneslas.vahter.probe.extra

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import ru.poneslas.vahter.probe.TAG

/**
 * Запуск чужого интента «из листа»: лист зовёт startAssistantActivity(этот батут), батут — startActivity(target).
 * У сессии нет startActivity, поэтому только так (П16, П17).
 */
class ExtraTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        val target = intent.getParcelableExtra(EXTRA_TARGET, Intent::class.java)
        if (target != null) {
            runCatching { startActivity(target) }
                .onSuccess { Log.i(TAG, "extra: из листа запущен ${target.action}") }
                .onFailure { Log.w(TAG, "extra: из листа ${target.action} не запустился", it); lastError = "${it.javaClass.simpleName}: ${it.message}" }
        }
        finish()
    }

    companion object {
        const val EXTRA_TARGET = "target"
        /** Ошибка последнего запуска из листа — проверка забирает её в свой лог. */
        @Volatile var lastError: String? = null
    }
}
