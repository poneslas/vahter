package ru.poneslas.tasya.probe

import android.content.Context
import ru.poneslas.tasya.probe.asr.AsrModule
import ru.poneslas.tasya.probe.assist.AssistModule
import ru.poneslas.tasya.probe.assist.SheetAction
import ru.poneslas.tasya.probe.camera.CameraModule
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.entry.EntryModule
import ru.poneslas.tasya.probe.expand.ExpandModule
import ru.poneslas.tasya.probe.extra.ExtraModule
import ru.poneslas.tasya.probe.net.NetModule
import ru.poneslas.tasya.probe.pick.PickModule
import ru.poneslas.tasya.probe.push.PushModule
import ru.poneslas.tasya.probe.session.SessionModule
import ru.poneslas.tasya.probe.update.UpdateModule
import ru.poneslas.tasya.probe.upload.UploadModule

/** Пакет проверок: свои проверки и свои кнопки в листе. */
interface Module {
    val probes: List<Probe>
    val actions: List<SheetAction> get() = emptyList()
    fun init(ctx: Context) {}
}

object Registry {
    private val modules: List<Module> = listOf(
        AssistModule, SessionModule, CameraModule, PickModule, UploadModule, ExpandModule,
        EntryModule, PushModule, NetModule, UpdateModule, AsrModule, ExtraModule,
    )

    lateinit var probes: List<Probe>
        private set
    lateinit var sheetActions: List<SheetAction>
        private set

    fun init(ctx: Context) {
        modules.forEach { it.init(ctx) }
        probes = modules.flatMap { it.probes }.sortedBy { it.id.drop(1).toIntOrNull() ?: 99 }
        sheetActions = modules.flatMap { it.actions }
    }

    fun probe(id: String) = probes.first { it.id == id }
}
