package ru.poneslas.tasya.probe.upload

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.tasya.probe.App
import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.assist.SheetAction
import ru.poneslas.tasya.probe.core.Config
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.Status
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object UploadModule : Module {
    override val probes = listOf(UploadProbe)

    override val actions = Variant.entries.map { v ->
        SheetAction("upload", "Загрузить: ${v.label}") { _ ->
            // Ручные опыты (выключить экран, am kill): ход пишется в лог П9.
            val scope = ProbeScope(App.instance, UploadProbe.id)
            val id = newId(v)
            scope.log("manual_start", "${v.label} id=$id")
            runCatching { v.start(App.instance, id) }.onFailure { scope.error(it, "запуск ${v.label}") }
            App.scope.launch { watch(scope, id, v, 600_000) }
        }
    } + SheetAction("upload", "Статус сервера") { _ ->
        val scope = ProbeScope(App.instance, UploadProbe.id)
        scope.log("server_status", serverStatus() ?: "сервер недоступен")
    }
}

enum class Variant(val key: String, val label: String) {
    COROUTINE("coroutine", "корутина"),
    WORK("work", "WorkManager"),
    UIDT("uidt", "UIDT"),
    FGS("fgs", "FGS dataSync"),
    ;

    /** Запуск из текущего состояния процесса; исключение запуска пробрасывается. */
    fun start(ctx: Context, id: String) {
        Uploads.start(id, key)
        when (this) {
            COROUTINE -> thread(name = "coroutine-$id") { Uploads.uploadBlocking(id) }
            WORK -> WorkManager.getInstance(ctx).enqueue(
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(workDataOf("id" to id))
                    .build(),
            )
            UIDT -> {
                val js = ctx.getSystemService(JobScheduler::class.java)
                val job = JobInfo.Builder(id.hashCode(), ComponentName(ctx, UidtJobService::class.java))
                    .setUserInitiated(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setEstimatedNetworkBytes(Uploads.SIZE, 0)
                    .setExtras(PersistableBundle().apply { putString("id", id) })
                    .build()
                val r = js.schedule(job)
                if (r != JobScheduler.RESULT_SUCCESS) throw IllegalStateException("JobScheduler.schedule = $r")
            }
            FGS -> ctx.startForegroundService(Intent(ctx, DataSyncService::class.java).putExtra("id", id))
        }
    }
}

private fun newId(v: Variant) = "${v.key}-${System.currentTimeMillis() % 1_000_000}"

private suspend fun serverStatus(): String? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL("${Config.server}/status").openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 3000
        try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }.getOrNull()
}

/** Ждёт конца загрузки [id]; раз в 5 с пишет байты и важность процесса. */
private suspend fun watch(scope: ProbeScope, id: String, v: Variant, timeoutMs: Long): Progress? {
    var lastLog = 0L
    return withTimeoutOrNull(timeoutMs) {
        var p: Progress? = Uploads.all.value[id]
        while (p == null || !p.finished) {
            val now = SystemClock.uptimeMillis()
            if (now - lastLog >= 5000) {
                lastLog = now
                val mb = (p?.bytes ?: 0) / 1_048_576.0
                scope.log("progress", "${v.label}: %.1f МБ, importance=${Uploads.importance()}".format(mb))
                Sheet.setStatus("П9 ${v.label}: %.1f из 20 МБ".format(mb))
            }
            delay(500)
            p = Uploads.all.value[id]
        }
        p
    }.also { p ->
        val r = p ?: Uploads.all.value[id]
        scope.log("result", "${v.label}: ok=${r?.ok} bytes=${r?.bytes} error=${r?.error} stop=${r?.stopReason}" + if (p == null) " (таймаут)" else "")
    }
}

object UploadProbe : Probe(
    "П9", "Загрузка вложения, если лист закрыли",
    "20 МБ уходят на тестовый сервер; лист закрывается через 2 с после старта. Какой из вариантов догружает.",
    needsHuman = false,
    steps = listOf(
        "На Mac: python3 probe/scripts/serve.py (2 Мбит/с). Телефону — адрес Mac в «Отчёт и настройки → Тестовый сервер».",
        "Автоматически по очереди: корутина, WorkManager expedited, UIDT, FGS dataSync — открыть лист, запустить, через 2 с закрыть, ждать итог (до 3 мин).",
        "Руками (кнопки «Загрузить: …» в листе): запустить, закрыть лист и сразу выключить экран; ещё раз — и посреди загрузки на Mac `adb shell am kill ru.poneslas.tasya.probe`. Итог — «Статус сервера» в листе.",
        "На эмуляторе дросселирование слабее, чем на One UI: решающий прогон — на телефоне.",
    ),
) {
    private const val TIMEOUT_MS = 180_000L

    override suspend fun ProbeScope.run(): Status {
        val st = serverStatus()
        if (st == null) {
            summary("Сервер ${Config.server} недоступен — запустите scripts/serve.py и проверьте адрес")
            return Status.FAIL
        }
        log("server", "${Config.server} доступен")
        val passed = mutableListOf<String>()
        val lines = mutableListOf<String>()
        for (v in Variant.entries) {
            val id = newId(v)
            val open = Sheet.open()
            if (open == null) {
                summary("Лист не открылся — нет роли помощника?")
                return Status.FAIL
            }
            log("start", "${v.label} id=$id importance=${Uploads.importance()}")
            val started = runCatching { v.start(ctx, id) }
            started.exceptionOrNull()?.let { error(it, "запуск ${v.label}") }
            delay(2000)
            Sheet.close()
            log("sheet_closed", "importance=${Uploads.importance()}")
            val p = if (started.isSuccess) watch(this, id, v, TIMEOUT_MS) else null
            val r = Uploads.all.value[id]
            r?.tFirstByte?.let { metric("${v.key}_start_ms", it - r.tCall) }
            metric("${v.key}_mb", (r?.bytes ?: 0) / 1_048_576.0)
            metric("${v.key}_ok", if (p?.ok == true) 1 else 0)
            if (r?.tEnd != null && r.tFirstByte != null) metric("${v.key}_seconds", (r.tEnd - r.tFirstByte) / 1000.0)
            lines += "${v.label}: " + when {
                started.isFailure -> "не запустился (${started.exceptionOrNull()?.javaClass?.simpleName})"
                p?.ok == true -> "догрузил"
                p == null -> "не успел за ${TIMEOUT_MS / 1000} с (%.1f МБ)".format((r?.bytes ?: 0) / 1_048_576.0)
                else -> "оборвался: ${r?.error ?: r?.stopReason}"
            }
            if (p?.ok == true) passed += v.label
            Sheet.setStatus("")
            delay(1000)
        }
        log("server_status", serverStatus() ?: "сервер недоступен")
        summary(lines.joinToString("; ") + ". Экран выкл. и am kill — руками, см. шаг 3.")
        // «Прошло» по документу требует ещё выключенного экрана — это ручной шаг 3,
        // итог человек ставит кнопкой на экране проверки.
        return if (passed.isNotEmpty()) Status.PARTIAL else Status.FAIL
    }
}
