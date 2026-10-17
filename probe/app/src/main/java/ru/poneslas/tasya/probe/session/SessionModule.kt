package ru.poneslas.tasya.probe.session

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object SessionModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П3", "Окно сессии: Compose, кадры, клавиатура", "", "ещё не написана"),
        NotImplementedProbe("П4", "Микрофон в окне без активности; процесс после скрытия", "", "ещё не написана"),
        NotImplementedProbe("П5", "Поверх блокировки: лист, микрофон, разблокировка", "", "ещё не написана"),
    )
}
