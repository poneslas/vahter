package ru.poneslas.tasya.probe.expand

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object ExpandModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П10", "Развернуть лист в приложение и обратно", "", "ещё не написана"),
    )
}
