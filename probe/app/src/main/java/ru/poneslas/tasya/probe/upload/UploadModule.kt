package ru.poneslas.tasya.probe.upload

import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.core.NotImplementedProbe

object UploadModule : Module {
    override val probes = listOf(
        NotImplementedProbe("П9", "Загрузка вложения, если лист закрыли", "", "ещё не написана"),
    )
}
