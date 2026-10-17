package ru.poneslas.tasya.probe.notify

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object NotifyModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П23", "Ответ после закрытия листа и уведомления v1 без пуша", "", "ещё не написана"),
    )
}
