package ru.poneslas.tasya.probe.camera

import android.app.Activity
import android.os.Bundle

class ImageCaptureTrampoline : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
