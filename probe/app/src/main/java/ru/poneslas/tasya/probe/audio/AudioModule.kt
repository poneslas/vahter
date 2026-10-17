package ru.poneslas.tasya.probe.audio

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object AudioModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П24", "Аудиоокружение: звонок, гарнитура, лимитная сеть", "", "ещё не написана"),
    )
}
