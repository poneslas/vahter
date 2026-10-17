package ru.poneslas.tasya.probe.camera

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object CameraModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П6", "Камера сразу по вызову: три способа", "", "ещё не написана"),
    )
}
