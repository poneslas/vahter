package ru.poneslas.vahter.probe

import android.content.Context
import ru.poneslas.vahter.probe.asr.AsrModule
import ru.poneslas.vahter.probe.assist.AssistModule
import ru.poneslas.vahter.probe.assist.SheetAction
import ru.poneslas.vahter.probe.camera.CameraModule
import ru.poneslas.vahter.probe.core.Probe
import ru.poneslas.vahter.probe.entry.EntryModule
import ru.poneslas.vahter.probe.expand.ExpandModule
import ru.poneslas.vahter.probe.extra.ExtraModule
import ru.poneslas.vahter.probe.net.NetModule
import ru.poneslas.vahter.probe.osread.OsReadModule
import ru.poneslas.vahter.probe.perm.PermModule
import ru.poneslas.vahter.probe.actions.ActionsModule
import ru.poneslas.vahter.probe.notify.NotifyModule
import ru.poneslas.vahter.probe.audio.AudioModule
import ru.poneslas.vahter.probe.keys.KeysModule
import ru.poneslas.vahter.probe.guide.GuideModule
import ru.poneslas.vahter.probe.pick.PickModule
import ru.poneslas.vahter.probe.push.PushModule
import ru.poneslas.vahter.probe.session.SessionModule
import ru.poneslas.vahter.probe.update.UpdateModule
import ru.poneslas.vahter.probe.upload.UploadModule

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
        PermModule, ActionsModule, NotifyModule, AudioModule, KeysModule, GuideModule,
    )

    lateinit var probes: List<Probe>
        private set
    lateinit var sheetActions: List<SheetAction>
        private set

    fun init(ctx: Context) {
        modules.forEach { it.init(ctx) }
        // «П3ч» встаёт сразу за «П3»: сортируем по номеру, потом по суффиксу.
        probes = modules.flatMap { it.probes }.sortedWith(
            compareBy({ !it.id.startsWith("П") }, { Regex("\\d+").find(it.id)?.value?.toInt() ?: 99 }, { it.id }),
        )
        sheetActions = modules.flatMap { it.actions }
    }

    fun probe(id: String) = probes.first { it.id == id }
}
