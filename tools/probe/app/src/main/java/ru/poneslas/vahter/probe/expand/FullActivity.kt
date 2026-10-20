package ru.poneslas.vahter.probe.expand

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.vahter.probe.App
import ru.poneslas.vahter.probe.TAG
import ru.poneslas.vahter.probe.assist.OpenRecord
import ru.poneslas.vahter.probe.assist.ProbeSession
import ru.poneslas.vahter.probe.assist.Sheet

/** Связка листа и развёрнутой активности (П10). Времена — uptimeMillis. */
object ExpandBus {
    val frames = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val destroyed = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    @Volatile var current: FullActivity? = null

    /**
     * «Развернуть»: активность помощника без анимации; лист прячет сама активность по первому кадру.
     * Возвращает время «нажал → первый кадр активности» или null; ошибку запуска — через [onError].
     */
    suspend fun expand(session: ProbeSession, onError: (Throwable) -> Unit = {}): Long? = coroutineScope {
        val wait = async { withTimeoutOrNull(3000) { frames.first() } }
        val t0 = SystemClock.uptimeMillis()
        try {
            val opts = ActivityOptions.makeCustomAnimation(session.context, 0, 0).toBundle()
            session.startAssistantActivity(
                Intent(session.context, FullActivity::class.java).putExtra(FullActivity.EXTRA_HIDE, true),
                opts,
            )
            Log.i(TAG, "П10 startAssistantActivity ok")
        } catch (t: Throwable) {
            // На скрытой сессии система отвечает START_ASSISTANT_HIDDEN_SESSION → исключение [AOSP]
            Log.w(TAG, "П10 startAssistantActivity упал", t)
            onError(t)
            wait.cancel()
            return@coroutineScope null
        }
        wait.await()?.let { it - t0 }
    }

    /**
     * «Свернуть»: лист снова показать, активность закрыть без анимации.
     * [showFirst] — сначала showSession, потом finish (иначе наоборот). Возвращает «нажал → кадр листа».
     */
    suspend fun collapse(showFirst: Boolean): OpenRecord? = coroutineScope {
        val act = current ?: return@coroutineScope null
        val svc = Sheet.awaitService() ?: return@coroutineScope null
        val wait = async { withTimeoutOrNull(3000) { Sheet.opens.first() } }
        Sheet.pendingCall = SystemClock.uptimeMillis()
        val args = Bundle().apply { putString("src", "collapse") }
        if (showFirst) {
            svc.showSession(args, 0)
            act.finishQuietly()
        } else {
            act.finishQuietly()
            svc.showSession(args, 0)
        }
        wait.await()
    }
}

/** Развёрнутый лист — обычная активность помощника на весь экран. */
class FullActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        ExpandBus.current = this
        Log.i(TAG, "FullActivity onCreate task=$taskId root=$isTaskRoot")
        val hide = intent.getBooleanExtra(EXTRA_HIDE, false)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Color(0xFFECECEC)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Развёрнутый лист (П10)", style = MaterialTheme.typography.titleLarge)
                        Text("Проверьте: «Назад», «Домой», есть ли это окно в «Недавних».")
                        Button({ App.scope.launch { ExpandBus.collapse(showFirst = true) } }) { Text("Свернуть (сначала лист)") }
                        Button({ App.scope.launch { ExpandBus.collapse(showFirst = false) } }) { Text("Свернуть (сначала закрыть)") }
                    }
                }
            }
        }
        val decor = window.decorView
        decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            var done = false
            override fun onDraw() {
                if (done) return
                done = true
                val t = SystemClock.uptimeMillis()
                decor.post { decor.viewTreeObserver.removeOnDrawListener(this) }
                Log.i(TAG, "FullActivity первый кадр")
                ExpandBus.frames.tryEmit(t)
                if (hide) Sheet.session.value?.hide()
            }
        })
    }

    fun finishQuietly() {
        finish()
    }

    override fun onDestroy() {
        Log.i(TAG, "FullActivity onDestroy")
        if (ExpandBus.current === this) ExpandBus.current = null
        ExpandBus.destroyed.tryEmit(SystemClock.uptimeMillis())
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HIDE = "hideSession"
    }
}
