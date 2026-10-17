package ru.poneslas.tasya.probe.update

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object UpdateModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П14", "Самообновление без окна и роль после него", "", "ещё не написана"),
    )
}
