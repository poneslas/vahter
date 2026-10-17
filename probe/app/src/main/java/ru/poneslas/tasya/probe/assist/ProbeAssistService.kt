package ru.poneslas.tasya.probe.assist

import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.util.Log
import ru.poneslas.tasya.probe.TAG

class ProbeAssistService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Sheet.service = this
        Sheet.serviceReady.value = true
        Log.i(TAG, "VIS onReady")
    }

    override fun onShutdown() {
        Log.i(TAG, "VIS onShutdown")
        Sheet.service = null
        Sheet.serviceReady.value = false
        super.onShutdown()
    }

    /** Самая ранняя точка в нашем коде при открытии (API 33). */
    override fun onPrepareToShowSession(args: Bundle, showFlags: Int) {
        Sheet.pendingPrepare = SystemClock.uptimeMillis()
        Log.i(TAG, "VIS onPrepareToShowSession flags=0x${Integer.toHexString(showFlags)} [${showFlags(showFlags)}] args=${args.keySet()}")
    }

    override fun onShowSessionFailed(args: Bundle) {
        Log.w(TAG, "VIS onShowSessionFailed args=${args.keySet()}")
        Sheet.pendingPrepare = null
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        Log.i(TAG, "VIS onLaunchVoiceAssistFromKeyguard")
        showSession(Bundle().apply { putString("src", "keyguard") }, 0)
    }
}
