package ru.poneslas.tasya.probe.session

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.assist.SheetAction

object SessionModule : Module {
    override val probes = listOf(WindowProbe, MicProbe, LockProbe, WindowHumanProbe)
    override val actions = listOf(
        SheetAction("session", "Записать 5 с") { s ->
            Sheet.setStatus("Запись 5 с…")
            Sheet.setStatus("Запись: " + recordFor(s.context, 5000).describe())
        },
        SheetAction("session", "Разблокировать (П5)") { s -> UnlockBus.start(s) },
    )
}
