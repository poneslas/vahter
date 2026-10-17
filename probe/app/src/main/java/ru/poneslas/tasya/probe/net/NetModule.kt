package ru.poneslas.tasya.probe.net

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object NetModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П13", "Сервер в локальной сети, Auto Blocker", "", "ещё не написана"),
    )
}
