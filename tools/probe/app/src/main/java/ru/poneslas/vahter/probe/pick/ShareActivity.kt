package ru.poneslas.vahter.probe.pick

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch
import ru.poneslas.vahter.probe.App
import ru.poneslas.vahter.probe.TAG
import ru.poneslas.vahter.probe.camera.returnToSheet

/** 8.4: «Поделиться → В пробник Вахтёра» из Галереи: копия → лист с вложением через showSession. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tResult = PickBus.now()
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        Log.i(TAG, "ShareActivity uri=$uri type=${intent.type}")
        if (uri == null) {
            PickBus.emit(PickEvent.Failed("share", "в ACTION_SEND нет EXTRA_STREAM"))
            finish()
            return
        }
        App.scope.launch {
            try {
                val c = copyToAttachments(applicationContext, uri, "share")
                PickBus.emit(PickEvent.Picked("share", uri, c.path, c.mime, c.bytes, c.ms, tResult))
                finish()
                returnToSheet(c.path, "share")
                PickBus.emit(PickEvent.Returned("share", "showSession", PickBus.now()))
            } catch (t: Throwable) {
                PickBus.emit(PickEvent.Failed("share", "копия: ${t.javaClass.simpleName}: ${t.message}"))
                finish()
            }
        }
    }
}
