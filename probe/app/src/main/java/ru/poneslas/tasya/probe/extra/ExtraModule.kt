package ru.poneslas.tasya.probe.extra

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object ExtraModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П16", "Settings.Panel на One UI", "", "ещё не написана"),
        NotImplementedProbe("П17", "Интенты «Часов» Samsung", "", "ещё не написана"),
    )
}
