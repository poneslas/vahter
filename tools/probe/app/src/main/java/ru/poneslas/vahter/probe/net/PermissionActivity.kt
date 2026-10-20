package ru.poneslas.vahter.probe.net

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import ru.poneslas.vahter.probe.TAG

/** Прозрачная активность: запрашивает одно разрешение и отдаёт результат в [pending]. */
class PermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val perm = intent.getStringExtra(EXTRA_PERMISSION) ?: return finish()
            requestPermissions(arrayOf(perm), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "PermissionActivity ${permissions.firstOrNull()} granted=$granted")
        pending?.complete(granted)
        pending = null
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) {
            pending?.complete(false)
            pending = null
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PERMISSION = "permission"
        @Volatile var pending: CompletableDeferred<Boolean>? = null
    }
}
