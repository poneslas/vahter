package ru.poneslas.vahter.probe.perm

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import ru.poneslas.vahter.probe.TAG

/**
 * Прозрачный батут П19/П20. Два режима:
 *  - [EXTRA_PERMISSIONS]: requestPermissions, итог в [PermBus.perm];
 *  - [EXTRA_TARGET]: запускает чужой экран, возврат «Назад» на батут → [PermBus.returned].
 * Из листа запускается через startAssistantActivity, из приложения — обычным startActivity.
 */
class PermTrampolineActivity : Activity() {
    private var resumes = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        PermBus.tCreate = SystemClock.uptimeMillis()
        alive = true
        if (savedInstanceState != null) return
        val perms = intent.getStringArrayExtra(EXTRA_PERMISSIONS)
        val target = intent.getParcelableExtra(EXTRA_TARGET, Intent::class.java)
        when {
            perms != null -> requestPermissions(perms, 1)
            target != null -> try {
                startActivity(target)
                PermBus.launched?.complete(null)
            } catch (t: Throwable) {
                Log.w(TAG, "perm: ${target.action} не запустился", t)
                PermBus.launched?.complete("${t.javaClass.simpleName}: ${t.message}")
                finish()
            }
            else -> finish()
        }
    }

    override fun onResume() {
        super.onResume()
        resumes++
        // Первый onResume — сам батут до чужого экрана; второй — человек вернулся «Назад».
        if (intent.hasExtra(EXTRA_TARGET) && resumes >= 2) {
            PermBus.returned?.complete(SystemClock.uptimeMillis())
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val r = PermResult(
            granted = permissions.indices.associate { permissions[it] to (grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED) },
            rationale = permissions.associateWith { shouldShowRequestPermissionRationale(it) },
            tResult = SystemClock.uptimeMillis(),
        )
        Log.i(TAG, "perm: результат $r")
        PermBus.perm?.complete(r)
        finish()
    }

    override fun onDestroy() {
        alive = false
        if (isFinishing) {
            PermBus.perm?.complete(PermResult(emptyMap(), emptyMap(), SystemClock.uptimeMillis()))
            PermBus.launched?.complete("батут закрыт до запуска")
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PERMISSIONS = "permissions"
        /** Батут на экране (для обхода «спрятав лист»). */
        @Volatile var alive = false
        const val EXTRA_TARGET = "target"
    }
}

/** Итог запроса: пустой [granted] — окно закрыли без ответа или оно не показалось. */
data class PermResult(val granted: Map<String, Boolean>, val rationale: Map<String, Boolean>, val tResult: Long)

object PermBus {
    @Volatile var tCreate = 0L
    @Volatile var perm: CompletableDeferred<PermResult>? = null
    @Volatile var launched: CompletableDeferred<String?>? = null
    @Volatile var returned: CompletableDeferred<Long>? = null
}
