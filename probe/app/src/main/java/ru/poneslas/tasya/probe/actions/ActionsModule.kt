package ru.poneslas.tasya.probe.actions

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object ActionsModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П21", "Действия на телефоне: фонарик, громкость, «Не беспокоить», медиа, место, календарь", "", "ещё не написана"),
        NotImplementedProbe("П22", "Чужие экраны из листа", "", "ещё не написана"),
    )
}
