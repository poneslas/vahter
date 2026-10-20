package ru.poneslas.vahter.probe.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import kotlinx.coroutines.delay
import ru.poneslas.vahter.probe.Module
import ru.poneslas.vahter.probe.assist.Sheet
import ru.poneslas.vahter.probe.core.Probe
import ru.poneslas.vahter.probe.core.ProbeScope
import ru.poneslas.vahter.probe.core.Status

object AudioModule : Module {
    override val probes = listOf(AudioEnvProbe, AudioHumanProbe)
}

/** Текущая сеть: транспорт и лимитная ли (для «240 МБ по мобильной — спросим», Продукт 5.13). */
fun networkLine(ctx: Context): String {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val n = cm.activeNetwork ?: return "сети нет"
    val c = cm.getNetworkCapabilities(n) ?: return "нет NetworkCapabilities"
    val transports = listOf(
        NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi", NetworkCapabilities.TRANSPORT_CELLULAR to "мобильная",
        NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet", NetworkCapabilities.TRANSPORT_VPN to "прокси",
        NetworkCapabilities.TRANSPORT_BLUETOOTH to "Bluetooth",
    ).filter { c.hasTransport(it.first) }.joinToString("+") { it.second }.ifEmpty { "?" }
    return "$transports; isActiveNetworkMetered=${cm.isActiveNetworkMetered}; NOT_METERED=${c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}; " +
        "TEMPORARILY_NOT_METERED=${c.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)}"
}

/** Состояние звонка: AudioManager.mode без разрешений, TelephonyManager.callState — только с READ_PHONE_STATE [док]. */
fun callLine(ctx: Context): String {
    val am = ctx.getSystemService(AudioManager::class.java)
    val perm = ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    val call = runCatching {
        when (ctx.getSystemService(TelephonyManager::class.java).callState) {
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            else -> "?"
        }
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
    return "AudioManager.mode=${modeName(am.mode)}; callState=$call (READ_PHONE_STATE=$perm); музыка играет=${am.isMusicActive}"
}

private fun inputs(ctx: Context) = ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

object AudioEnvProbe : Probe(
    "П24", "Аудиоокружение: что видно без человека",
    "Текущая сеть (лимитная или нет), состояние звонка, список устройств ввода и устройств связи, запись 3 с из листа — чей микрофон.",
    needsHuman = false,
) {
    override suspend fun ProbeScope.run(): Status {
        val am = ctx.getSystemService(AudioManager::class.java)
        log("network", networkLine(ctx))
        log("call", callLine(ctx))
        inputs(ctx).forEach { log("input", deviceName(it)) }
        am.availableCommunicationDevices.forEach { log("comm_device", deviceName(it)) }
        log("comm_current", deviceName(am.communicationDevice))
        val r = Sheet.open()
        log("open", r?.describe() ?: "лист не открылся")
        val rec = record(ctx, 3000)
        log("record", rec.describe())
        Sheet.close()
        metric("inputs", inputs(ctx).size)
        summary("сеть: ${networkLine(ctx)}; ${callLine(ctx)}; запись из листа: ${if (rec.ok) rec.configDevice else rec.error}")
        return if (rec.ok) Status.PASS else Status.PARTIAL
    }
}

object AudioHumanProbe : Probe(
    "П24ч", "Аудиоокружение: звонок, Bluetooth и проводная гарнитура, лимитная сеть",
    "Лист во время звонка; запись через Bluetooth-гарнитуру (preferredDevice и setCommunicationDevice); гарнитура подключается посреди фразы; признак лимитной сети на Wi-Fi, мобильной и точке доступа.",
    needsHuman = true,
    steps = listOf(
        "Звонок: позвонить на телефон или с него, не класть трубку; пробник откроет лист и запишет 3 с.",
        "Bluetooth-гарнитура: подключить; пробник пишет 3 с двумя способами.",
        "Проводная (или USB) гарнитура: подключить во время 10-секундной записи.",
        "Сеть: переключить Wi-Fi → мобильная → точка доступа с другого телефона.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        val am = ctx.getSystemService(AudioManager::class.java)
        val lines = mutableListOf<String>()
        val verdicts = mutableListOf<Status>()
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = added.forEach { log("device_added", deviceName(it)) }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = removed.forEach { log("device_removed", deviceName(it)) }
        }
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        try {
            // 1. Звонок
            if (ask("Шаг 1: позвоните на телефон (или с него) и не кладите трубку. Разговор идёт?", listOf("Идёт", "Пропустить")) == "Идёт") {
                log("call", callLine(ctx))
                val inCall = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
                Sheet.open()
                val rec = record(ctx, 3000)
                log("record_in_call", rec.describe())
                Sheet.close()
                lines += "звонок: mode=${modeName(am.mode)}, распознан=$inCall, запись: ${rec.describe()}"
                verdicts += if (inCall) Status.PASS else Status.PARTIAL
                tell("Можно положить трубку.")
            }

            // 2. Bluetooth
            if (ask("Шаг 2: подключите Bluetooth-гарнитуру и наденьте её.", listOf("Подключена", "Пропустить")) == "Подключена") {
                inputs(ctx).forEach { log("input", deviceName(it)) }
                val btIn = inputs(ctx).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                Sheet.open()
                val a = record(ctx, 3000, preferred = btIn)
                log("bt_preferred", "preferred=${deviceName(btIn)}: ${a.describe()}")
                val comm = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                val set = comm?.let { runCatching { am.setCommunicationDevice(it) }.getOrElse { e -> log("error", "setCommunicationDevice: $e"); false } } ?: false
                log("bt_comm", "setCommunicationDevice(${deviceName(comm)})=$set, mode=${modeName(am.mode)}")
                delay(500)
                val b = record(ctx, 3000, source = MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                log("bt_comm_record", b.describe())
                am.clearCommunicationDevice()
                Sheet.close()
                val heard = ask("Говорили в гарнитуру. Запись: preferredDevice — ${a.configDevice}; setCommunicationDevice — ${b.configDevice}. Совпадает с гарнитурой хоть один?", listOf("Да", "Нет"))
                lines += "BT: устройство ввода ${deviceName(btIn)}; preferred → ${a.configDevice}; comm → ${b.configDevice} ($heard)"
                verdicts += if (heard == "Да") Status.PASS else if (btIn == null) Status.FAIL else Status.PARTIAL
            }

            // 3. Проводная посреди фразы
            if (ask("Шаг 3: держите проводную (или USB) гарнитуру наготове. После «Старт» идёт запись 10 с — подключите гарнитуру посередине и говорите.", listOf("Старт", "Пропустить")) == "Старт") {
                Sheet.open()
                val r = record(ctx, 10_000)
                Sheet.close()
                log("wired_midway", r.describe())
                lines += "подключение посреди записи: ${if (r.routeChanges.isEmpty()) "маршрут не сменился" else r.routeChanges.joinToString()}"
                verdicts += if (r.ok) Status.PASS else Status.FAIL
            }

            // 4. Лимитная сеть
            if (ask("Шаг 4: признак лимитной сети. Сейчас: ${networkLine(ctx)}. Проверяем три сети?", listOf("Да", "Пропустить")) == "Да") {
                val nets = mutableListOf<String>()
                for (n in listOf("только Wi-Fi", "только мобильная (Wi-Fi выключить)", "точка доступа с другого телефона")) {
                    if (ask("Включите: $n.", listOf("Готово", "Пропустить")) == "Пропустить") continue
                    delay(1500)
                    val line = networkLine(ctx)
                    log("network", "$n: $line")
                    nets += "$n → $line"
                }
                lines += nets
                val right = ask("Мобильная и точка доступа помечены как лимитные (metered=true), Wi-Fi — нет?", listOf("Да", "Нет"))
                verdicts += if (right == "Да") Status.PASS else Status.PARTIAL
            }
        } finally {
            am.unregisterAudioDeviceCallback(cb)
            am.clearCommunicationDevice()
        }
        summary(lines.joinToString("; ").ifEmpty { "все шаги пропущены" })
        return when {
            verdicts.isEmpty() -> Status.NOT_RUN
            verdicts.all { it == Status.PASS } -> Status.PASS
            verdicts.all { it == Status.FAIL } -> Status.FAIL
            else -> Status.PARTIAL
        }
    }
}
