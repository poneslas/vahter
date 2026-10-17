package ru.poneslas.tasya.probe.push

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object PushModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П12", "Пуш ntfy открывает лист", "", "ещё не написана"),
    )
}
