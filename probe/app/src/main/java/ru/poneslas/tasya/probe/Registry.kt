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
import ru.poneslas.tasya.probe.osread.OsReadModule
import ru.poneslas.tasya.probe.perm.PermModule
import ru.poneslas.tasya.probe.actions.ActionsModule
import ru.poneslas.tasya.probe.notify.NotifyModule
import ru.poneslas.tasya.probe.audio.AudioModule
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
        EntryModule, PushModule, NetModule, UpdateModule, AsrModule, ExtraModule, OsReadModule,
        PermModule, ActionsModule, NotifyModule, AudioModule,
    )

    lateinit var probes: List<Probe>
        private set
    lateinit var sheetActions: List<SheetAction>
        private set

    fun init(ctx: Context) {
        modules.forEach { it.init(ctx) }
        // «П3ч» встаёт сразу за «П3»: сортируем по номеру, потом по суффиксу.
        probes = modules.flatMap { it.probes }.sortedWith(
            compareBy({ Regex("\\d+").find(it.id)?.value?.toInt() ?: 99 }, { it.id }),
        )
        sheetActions = modules.flatMap { it.actions }
    }

    fun probe(id: String) = probes.first { it.id == id }
}
