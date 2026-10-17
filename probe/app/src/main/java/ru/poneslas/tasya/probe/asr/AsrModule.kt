package ru.poneslas.tasya.probe.asr

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object AsrModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П15", "Локальное распознавание: загрузка, холодный старт, задержка", "", "ещё не написана"),
    )
}
