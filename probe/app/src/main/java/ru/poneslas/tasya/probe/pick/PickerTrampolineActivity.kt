package ru.poneslas.tasya.probe.pick

import android.app.Activity
import android.os.Bundle

class PickerTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
