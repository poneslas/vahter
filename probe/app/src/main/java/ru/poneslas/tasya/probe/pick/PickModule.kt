package ru.poneslas.tasya.probe.pick

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object PickModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П8", "Файл и фото из галереи из листа", "", "ещё не написана"),
    )
}
