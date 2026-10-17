package ru.poneslas.tasya.probe.push

import android.app.Activity
import android.os.Bundle

class NotifyTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
