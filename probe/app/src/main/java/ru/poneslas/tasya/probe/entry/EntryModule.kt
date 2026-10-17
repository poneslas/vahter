package ru.poneslas.tasya.probe.entry

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object EntryModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П11", "Ярлыки иконки и плитка открывают лист в режиме", "", "ещё не написана"),
    )
}
