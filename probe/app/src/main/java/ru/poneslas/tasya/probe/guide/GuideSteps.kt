package ru.poneslas.tasya.probe.guide

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.tasya.probe.App
import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.assist.SheetAction
import ru.poneslas.tasya.probe.camera.CamEvent
import ru.poneslas.tasya.probe.camera.CameraBus
import ru.poneslas.tasya.probe.camera.InSheetCamera
import ru.poneslas.tasya.probe.camera.Variant
import ru.poneslas.tasya.probe.core.Header
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.Status
import ru.poneslas.tasya.probe.core.fmt
import ru.poneslas.tasya.probe.core.median
import ru.poneslas.tasya.probe.keys.useAll
import ru.poneslas.tasya.probe.notify.Sse
import ru.poneslas.tasya.probe.perm.Components
import ru.poneslas.tasya.probe.pick.HideFirst
import ru.poneslas.tasya.probe.pick.PickBus
import ru.poneslas.tasya.probe.pick.PickEvent
import ru.poneslas.tasya.probe.pick.PickerTrampolineActivity
import ru.poneslas.tasya.probe.session.UnlockBus
import ru.poneslas.tasya.probe.session.recordFor
import ru.poneslas.tasya.probe.session.waitOrHuman

/**
 * Шаги ведущего прогона, где нужен человек. Каждый — одна короткая инструкция за раз; всё, что
 * можно, пробник делает сам (закрывает лист, пишет звук, проверяет ключи). Подробные проверки П* —
 * в «Все проверки».
 */
object GuideModule : Module {
    override val probes = listOf(ButtonStep, CameraButtonStep, LockStep, PickStep, AnswerOffStep, SideKeyStep, UpdateStep)

    override val actions = listOf(
        // На S24 окна, запущенные из листа, открываются под ним — экран разблокировки тоже: сначала прячем лист.
        SheetAction("guide_unlock", "Разблокировать") { s ->
            UnlockBus.tAction = android.os.SystemClock.uptimeMillis()
            HideFirst.launch(s, Intent(s.context, ru.poneslas.tasya.probe.session.UnlockActivity::class.java))
        },
        SheetAction("guide_photo", "Выбрать фото") { s ->
            HideFirst.launch(s, Intent(s.context, PickerTrampolineActivity::class.java).putExtra("kind", "photo"))
        },
        SheetAction("guide_file", "Выбрать файл") { s ->
            HideFirst.launch(s, Intent(s.context, PickerTrampolineActivity::class.java).putExtra("kind", "file"))
        },
    )
}

/** Шаг с человеком ждёт его действия, а не проваливается по тайм-ауту, пока он отошёл. */
private const val WAIT_HUMAN = 30 * 60_000L

private fun prefs(ctx: Context) = ctx.getSharedPreferences("guide", Context.MODE_PRIVATE)

/** Время открытия для человека: от нажатия, если система дала время нажатия, иначе от первой точки в нашем коде. */
private fun pressMs(r: ru.poneslas.tasya.probe.assist.OpenRecord) = r.invokeToFrame ?: r.prepareToFrame

object ButtonStep : Probe("Г1", "Боковая кнопка", "Удержание боковой кнопки открывает лист, и за сколько.", needsHuman = true) {
    override suspend fun ProbeScope.run(): Status {
        Sheet.close()
        val times = mutableListOf<Double>()
        for (i in 1..5) {
            val r = waitOrHuman("Удержите боковую кнопку. Раз $i из 5.", "Не открывается", WAIT_HUMAN) {
                Sheet.opens.first { it.tCall == null }
            }
            if (r == null) {
                summary(if (times.isEmpty()) "Лист не открылся. Проверьте: Настройки → Дополнительные функции → Боковая кнопка → «Цифровой помощник»." else "Открылся ${times.size} раз из 5")
                return if (times.isEmpty()) Status.FAIL else Status.PARTIAL
            }
            log("open", r.describe())
            metric("press_to_frame_ms", pressMs(r))
            times += pressMs(r).toDouble()
            delay(700)
            Sheet.close()
            delay(400)
        }
        val m = median(times)
        summary("Открывается 5 из 5, в среднем за ${fmt(m)} мс от нажатия (цель — до 300)")
        return if (m <= 300) Status.PASS else Status.PARTIAL
    }
}

object CameraButtonStep : Probe("Г2", "Камера с кнопки", "Удержание кнопки сразу открывает видоискатель на весь экран; снимок уходит в лист.", needsHuman = true) {
    override suspend fun ProbeScope.run(): Status {
        Sheet.close()
        CameraBus.armed = Variant.INSHEET_COMPAT
        val e = try {
            waitOrHuman("Удержите боковую кнопку — откроется камера.", "Не открывается", WAIT_HUMAN) {
                CameraBus.events.first { it.variant == Variant.INSHEET_COMPAT && (it is CamEvent.Streaming || it is CamEvent.Failed) }
            }
        } finally {
            CameraBus.armed = null
        }
        if (e !is CamEvent.Streaming) {
            summary(if (e is CamEvent.Failed) "Камера не открылась: ${e.msg}" else "Камера не открылась")
            Sheet.close(); InSheetCamera.stop()
            return Status.FAIL
        }
        val open = e.open
        val ms = open?.let { e.t - (it.tInvoke ?: it.tPrepare ?: it.tShow) }
        ms?.let { metric("press_to_viewfinder_ms", it) }
        val shot = waitOrHuman("Нажмите «Отправить».", "Пропустить", WAIT_HUMAN) {
            CameraBus.events.first { it.variant == Variant.INSHEET_COMPAT && (it is CamEvent.Captured || it is CamEvent.Failed) }
        }
        (shot as? CamEvent.Captured)?.let { metric("capture_ms", it.ms) }
        delay(800)
        Sheet.close()
        InSheetCamera.stop()
        summary("Видоискатель через ${ms ?: "?"} мс от нажатия (цель — до 700)" + if (shot is CamEvent.Captured) ", снимок за ${shot.ms} мс" else ", снимок не сделан")
        return when {
            ms != null && ms <= 700 && shot is CamEvent.Captured -> Status.PASS
            else -> Status.PARTIAL
        }
    }
}

object LockStep : Probe("Г3", "Заблокированный экран", "Лист поверх блокировки: микрофон, ключ API, разблокировка с возвратом в лист.", needsHuman = true) {
    override suspend fun ProbeScope.run(): Status {
        Sheet.close()
        val r = waitOrHuman("Заблокируйте телефон и удержите боковую кнопку.", "Не открывается", WAIT_HUMAN) {
            Sheet.opens.first { it.locked }
        }
        if (r == null) {
            summary("Лист на блокировке не открылся")
            return Status.FAIL
        }
        metric("locked_press_to_frame_ms", pressMs(r))
        Sheet.setStatus("Проверяю микрофон и ключи…")
        val mic = recordFor(ctx, 2000)
        log("mic", mic.describe())
        val keys = useAll("locked")
        val micOk = mic.ok && mic.silenced != true
        val keyOk = keys["p25_aes"] == true
        Sheet.setStatus("")

        Sheet.showGroups(setOf("guide_unlock"))
        val u = try {
            waitOrHuman("Нажмите в листе «Разблокировать» и приложите палец.", "Не получается", WAIT_HUMAN) { UnlockBus.events.first() }
        } finally {
            Sheet.showGroups(null)
        }
        log("unlock", u?.let { "${it.result}, экран через ${it.tCreate - it.tAction} мс, итог через ${it.tResult - it.tAction} мс" } ?: "нет события")
        val back = if (u?.result == "succeeded") withTimeoutOrNull(4000) { Sheet.opens.first() } else null
        delay(500)
        Sheet.close()
        summary(buildString {
            append("Лист на блокировке за ${pressMs(r)} мс")
            append("; микрофон ${if (micOk) "пишет" else "не пишет"}")
            append("; ключ API ${if (keyOk) "расшифрован" else if (keys.isEmpty()) "не проверен (нет ключей)" else "НЕ расшифрован"}")
            append("; разблокировка ${if (u?.result == "succeeded") "прошла" else "не прошла (${u?.result ?: "нет ответа"})"}")
            if (u?.result == "succeeded") append(", лист ${if (back != null) "вернулся" else "не вернулся"}")
        })
        return when {
            micOk && keyOk && back != null -> Status.PASS
            else -> Status.PARTIAL
        }
    }
}

object PickStep : Probe("Г4", "Фото и файл из листа", "Выбор фото и файла с обходом «сначала спрятать лист»: где окно и вернулся ли лист.", needsHuman = true) {
    override suspend fun ProbeScope.run(): Status {
        val res = linkedMapOf<String, String>()
        var good = 0
        for ((kind, label) in listOf("photo" to "Выбрать фото", "file" to "Выбрать файл")) {
            Sheet.showGroups(setOf("guide_$kind"))
            if (!Sheet.visible.value) Sheet.open()
            val e = try {
                waitOrHuman("Нажмите в листе «$label» и выберите любой.", "Пропустить", WAIT_HUMAN) {
                    PickBus.events.first { it.kind == kind && (it is PickEvent.Picked || it is PickEvent.Failed || it is PickEvent.Cancelled) }
                }
            } finally {
                Sheet.showGroups(null)
            }
            if (e !is PickEvent.Picked) {
                res[kind] = if (e is PickEvent.Failed) "ошибка: ${e.msg}" else "не выбрано"
                Sheet.close()
                continue
            }
            val back = withTimeoutOrNull(5000) { Sheet.opens.first { it.args.getString("src") == "pick_$kind" } }
            val where = ask("Где открылось окно выбора?", listOf("Поверх всего", "Под листом"))
            res[kind] = "окно: ${where.lowercase()}, лист ${if (back != null) "вернулся" else "не вернулся"}"
            if (where == "Поверх всего" && back != null) good++
            delay(400)
            Sheet.close()
            delay(400)
        }
        summary("Фото — ${res["photo"]}; файл — ${res["file"]}")
        return when (good) { 2 -> Status.PASS; 0 -> Status.FAIL; else -> Status.PARTIAL }
    }
}

object AnswerOffStep : Probe("Г5", "Ответ при выключенном экране", "Ответ дочитывается после закрытия листа при погашенном экране и приходит уведомлением; нажатие открывает лист.", needsHuman = true) {
    override suspend fun ProbeScope.run(): Status {
        if (ask("Нажмите «Начать», сразу погасите экран кнопкой питания и подождите 2 минуты.", listOf("Начать", "Пропустить")) != "Начать") {
            summary("Пропущено")
            return Status.PARTIAL
        }
        val id = "guide_${System.currentTimeMillis()}"
        val run = Sse.start(id, "coroutine")
        Sheet.open()
        App.scope.launch(Dispatchers.IO) { Sse.readBlocking(ctx.applicationContext, id, 90) }
        delay(2000)
        Sheet.close()
        withTimeoutOrNull(150_000) { while (!run.finished) delay(1000) }
        log("stream", "частей ${run.events} из 90, дочитан=${run.done}, уведомление=${run.notified}, ошибка=${run.error}")
        val seen = ask("Включите экран. Пришло уведомление «Тася ответила»?", listOf("Да", "Нет"))
        var opened = false
        if (seen == "Да") {
            val r = waitOrHuman("Нажмите на это уведомление.", "Лист не открылся", WAIT_HUMAN) { Sheet.opens.first() }
            opened = r != null
            delay(700)
            Sheet.close()
        }
        summary("Ответ ${if (run.done) "дочитан" else "НЕ дочитан (${run.events} из 90)"}; уведомление ${if (seen == "Да") "пришло" else "не пришло"}" +
            if (seen == "Да") "; по нажатию лист ${if (opened) "открылся" else "не открылся"}" else "")
        return when {
            run.done && seen == "Да" && opened -> Status.PASS
            run.done -> Status.PARTIAL
            else -> Status.FAIL
        }
    }
}

object SideKeyStep : Probe("Г6", "Видно ли настройку кнопки", "Может ли приложение прочитать, что назначено на удержание боковой кнопки, — чтобы предупредить «кнопка открывает Bixby».", needsHuman = true) {
    override suspend fun ProbeScope.run(): Status {
        log("side_key_screens", SideKeyScreen.candidates(ctx).joinToString { it.className }.ifEmpty { "не нашлось" })
        val before = snapshot(ctx)
        openSettings(ctx)
        if (ask("Смените действие удержания боковой кнопки на любое другое и вернитесь сюда.", listOf("Готово", "Пропустить")) != "Готово") {
            summary("Пропущено")
            return Status.PARTIAL
        }
        val after = snapshot(ctx)
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }
        changed.forEach { log("changed", "$it: ${before[it]} → ${after[it]}") }
        openSettings(ctx)
        ask("Верните «Цифровой помощник» и вернитесь сюда.", listOf("Готово"))
        val back = snapshot(ctx)
        val restored = changed.filter { back[it] == before[it] }
        summary(
            if (changed.isEmpty()) "Приложение не видит эту настройку — нужен запасной путь: «зажмите кнопку сейчас»"
            else "Видно: ${changed.joinToString { it.substringAfter('/') }}" + if (restored.isNotEmpty()) " (вернулось после возврата)" else "",
        )
        return if (changed.isNotEmpty()) Status.PASS else Status.FAIL
    }

    private fun openSettings(ctx: Context) {
        runCatching { ctx.startActivity(SideKeyScreen.intent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Все настройки, видимые приложению: system, secure, global. */
    private fun snapshot(ctx: Context): Map<String, String?> {
        val out = linkedMapOf<String, String?>()
        for (t in listOf("system", "secure", "global")) {
            runCatching {
                ctx.contentResolver.query(Uri.parse("content://settings/$t"), arrayOf("name", "value"), null, null, null)?.use { c ->
                    while (c.moveToNext()) out["$t/${c.getString(0)}"] = c.getString(1)
                }
            }
        }
        return out
    }
}

/**
 * Экран «Боковая кнопка» в настройках Samsung. Публичного интента нет, имя экрана не угадываем —
 * ищем среди открытых экранов приложения «Настройки» по имени (SideKey, FunctionKey…). Не нашли — корень настроек.
 */
object SideKeyScreen {
    private val name = Regex("side.?key|function.?key|side.?button|power.?key", RegexOption.IGNORE_CASE)

    fun candidates(ctx: Context): List<android.content.ComponentName> = runCatching {
        val pm = ctx.packageManager
        val acts = pm.getPackageInfo("com.android.settings", android.content.pm.PackageManager.GET_ACTIVITIES).activities.orEmpty()
        acts.filter { it.exported && name.containsMatchIn(it.name) }
            .sortedBy { if (Regex("settings", RegexOption.IGNORE_CASE).containsMatchIn(it.name.substringAfterLast('.'))) 0 else 1 }
            .map { android.content.ComponentName(it.packageName, it.name) }
    }.getOrDefault(emptyList())

    fun intent(ctx: Context): Intent =
        Components.get(ctx, "side_key")?.let { Intent().setComponent(it) }
            ?: candidates(ctx).firstOrNull()?.let { Intent().setComponent(it) }
            ?: Intent(Settings.ACTION_SETTINGS)
}

object UpdateStep : Probe("Г7", "После обновления", "После установки новой версии роль помощника и ключ API на месте.", needsHuman = true) {
    override suspend fun ProbeScope.run(): Status {
        val p = prefs(ctx)
        val vc = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        val marker = p.getLong("update_from", -1)
        if (marker < 0 || vc <= marker) {
            p.edit { putLong("update_from", vc) }
            tell("Установите поверх файл tasya-probe-2.apk и откройте пробник — проверка закончится сама.", "Понятно")
            summary("Ждём установки новой версии")
            return Status.PARTIAL
        }
        val role = Header.roleHeld(ctx)
        val keys = useAll("after_update")
        val keyOk = keys["p25_aes"] == true
        p.edit { remove("update_from") }
        summary("Версия $marker → $vc: роль помощника ${if (role) "на месте" else "СБРОШЕНА"}, ключ API ${if (keyOk) "на месте" else if (keys.isEmpty()) "не проверен" else "ПОТЕРЯН"}")
        return if (role && keyOk) Status.PASS else Status.FAIL
    }

    /** Новая версия уже стоит, а шаг ждёт — продолжить сам при запуске. */
    fun pending(ctx: Context): Boolean {
        val m = prefs(ctx).getLong("update_from", -1)
        return m >= 0 && ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode > m
    }
}
