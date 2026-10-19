package ru.poneslas.tasya.probe

import android.app.Application
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.poneslas.tasya.probe.core.Config
import ru.poneslas.tasya.probe.core.Results

const val TAG = "TasyaProbe"

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        createdAt = SystemClock.uptimeMillis()
        Log.i(TAG, "Application.onCreate pid=${Process.myPid()} processStart=${Process.getStartUptimeMillis()}")
        Config.init(this)
        Results.load(this)
        Registry.init(this)
    }

    companion object {
        lateinit var instance: App
            private set
        /** Время Application.onCreate — по нему П2/П3 отличают холодное открытие от тёплого. */
        var createdAt = 0L
            private set
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
