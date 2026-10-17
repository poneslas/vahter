package ru.poneslas.tasya.probe.perm

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object PermModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П19", "Переходы в настройки: интенты на One UI", "", "ещё не написана"),
        NotImplementedProbe("П20", "Разрешения из листа", "", "ещё не написана"),
    )
}
