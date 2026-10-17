package ru.poneslas.tasya.probe.entry

import android.app.Activity
import android.os.Bundle

class ShortcutTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
