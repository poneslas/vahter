package ru.poneslas.vahter.probe.session

import ru.poneslas.vahter.probe.Module
import ru.poneslas.vahter.probe.assist.Sheet
import ru.poneslas.vahter.probe.assist.SheetAction

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
