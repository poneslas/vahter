package ru.poneslas.tasya.probe.actions

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import ru.poneslas.tasya.probe.Module
import ru.poneslas.tasya.probe.assist.OpenRecord
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.Status

object ActionsModule : Module {
    override val probes = listOf(PhoneAutoProbe, PhoneHumanProbe, ForeignScreensProbe)
}

private fun sheetArgs() = Bundle().apply { putString("src", "actions") }

/** Ждёт открытия листа на заблокированном экране; null — человек сдался или 3 минуты прошли. */
suspend fun ProbeScope.waitLockedOpen(prompt: String): OpenRecord? = coroutineScope {
    val got = async { withTimeoutOrNull(180_000) { Sheet.opens.first { it.locked } } }
    val giveUp = async { ask(prompt, listOf("Не получается")) }
    while (!got.isCompleted && !giveUp.isCompleted) delay(200)
    giveUp.cancel()
    if (got.isCompleted) got.await() else { got.cancel(); null }
}

/** «Делаем / Пропустить» перед необязательным шагом. */
suspend fun ProbeScope.step(text: String) = ask(text, listOf("Делаем", "Пропустить")) == "Делаем"

object PhoneAutoProbe : Probe(
    "П21", "Действия на телефоне: фонарик, громкость, «Не беспокоить», место, календарь",
    "Без человека: каждое действие класса A/C из листа выполняется и возвращается как было.",
    needsHuman = false,
    steps = listOf(
        "Фонарик из открытого листа: вкл → TorchCallback → исходное.",
        "Громкость каждого потока на шаг и обратно; SecurityException без доступа к «Не беспокоить» — фиксируется.",
        "«Не беспокоить» своим AutomaticZenRule: вкл → фильтр сменился → выкл → правило удалено.",
        "Место из листа сразу после показа и через 5 с после закрытия (без GMS, LocationManager).",
        "Календарь (если выдано разрешение): перечень календарей, вставка тестового события и удаление.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        val fails = mutableListOf<String>()
        val untested = mutableListOf<String>()
        val notes = mutableListOf<String>()

        // 1. Фонарик из листа
        val open = Sheet.open(sheetArgs())
        log("sheet", open?.describe() ?: "лист не открылся")
        if (open == null) fails += "лист не открылся"
        val torch = torchCycle("sheet")
        if (torch != null) metric("torch_on_ms", torch) else untested += "фонарик (нет вспышки или ошибка)"

        // 2. Громкость
        val vol = volumeMatrix("sheet")
        if (vol.isNotEmpty()) notes += "громкость не далась: ${vol.joinToString()}"

        // 3. «Не беспокоить»
        try {
            val before = Dnd.filter(ctx)
            Dnd.setOn(ctx, true)
            delay(800)
            val on = Dnd.filter(ctx)
            Dnd.setOn(ctx, false)
            delay(800)
            val off = Dnd.filter(ctx)
            Dnd.remove(ctx)
            log("dnd", "фильтр: до=$before, вкл=$on, выкл=$off")
            if (on == before) fails += "«Не беспокоить» не включилось (фильтр $on)"
            if (off != before) fails += "«Не беспокоить» не вернулось ($off вместо $before)"
        } catch (e: Exception) {
            error(e, "dnd")
            fails += "«Не беспокоить»: ${e.javaClass.simpleName}"
            runCatching { Dnd.remove(ctx) }
        }

        // 4. Место из листа и после закрытия
        val (l1, t1) = oneLocation("sheet")
        if (t1 >= 0) metric("location_sheet_ms", t1)
        Sheet.close()
        delay(5000)
        val (l2, t2) = oneLocation("after_hide")
        if (t2 >= 0) metric("location_after_hide_ms", t2)
        when {
            t1 < 0 -> untested += "место (нет разрешения или выключено)"
            l1 == null -> fails += "место из листа не пришло"
        }
        if (t2 >= 0 && l2 == null) notes += "место после закрытия листа не пришло"

        // 5. Календарь
        if (granted(ctx, Manifest.permission.READ_CALENDAR) && granted(ctx, Manifest.permission.WRITE_CALENDAR)) {
            val cals = calendars()
            metric("calendars", cals.size)
            val cal = pickWritableCalendar(cals)
            if (cal == null) {
                untested += "календарь (нет доступного для записи)"
            } else {
                val id = insertEvent(cal, "Пробник Таси П21 (удалится)")
                if (id == null) fails += "событие не вставилось" else if (!deleteEvent(id)) fails += "событие не удалилось"
            }
        } else {
            untested += "календарь (нет разрешения — дайте на экране приложения и повторите)"
        }

        summary(buildString {
            if (fails.isNotEmpty()) append("не прошло: ${fails.joinToString()}. ")
            if (untested.isNotEmpty()) append("не проверено: ${untested.joinToString()}. ")
            if (notes.isNotEmpty()) append(notes.joinToString(". ") + ". ")
            if (fails.isEmpty() && untested.isEmpty()) append("всё выполнилось и вернулось как было")
        })
        return when {
            fails.isNotEmpty() -> Status.FAIL
            untested.isNotEmpty() || notes.isNotEmpty() -> Status.PARTIAL
            else -> Status.PASS
        }
    }
}

object PhoneHumanProbe : Probe(
    "П21ч", "Действия на телефоне с человеком: блокировка, «Режимы» One UI, медиа, календарь",
    "То, что видит только глаз или требует блокировки и выключенного экрана.",
    needsHuman = true,
    steps = listOf(
        "Фонарик на заблокированном экране (лист открыт кнопкой) и при открытом видоискателе (П6).",
        "«Не беспокоить»: виден ли наш режим в шторке и в «Режимах и процедурах»; выключение в срок через 5 минут при выключенном экране.",
        "Медиа: включите музыку в плеере; пробник шлёт пауза/играть/следующий и берёт аудиофокус как лист.",
        "Календарь: видно ли тестовое событие в Samsung Calendar и Google Calendar; после удаления — пропало ли.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        var bad = 0
        var done = 0

        if (step("Фонарик на блокировке?")) {
            val r = waitLockedOpen("Заблокируйте экран и откройте лист боковой кнопкой.")
            if (r != null) {
                val t = torchCycle("locked")
                val a = ask("Фонарик мигнул на ~0,6 с?", listOf("Да", "Нет"))
                done++
                if (t == null || a == "Нет") bad++
            }
        }
        if (step("Фонарик при открытом видоискателе? В листе нажмите «Камера в листе» до видоискателя, потом «Делаем».")) {
            val t = torchCycle("viewfinder")
            log("torch_viewfinder", if (t == null) "фонарик не дался при открытой камере — ошибка выше" else "включился за $t мс")
            ask("Что с видоискателем и фонариком?", listOf("Фонарик горел, видоискатель жив", "Видоискатель погас", "Фонарик не включился"))
            done++
        }

        if (step("«Не беспокоить»: включить наш режим?")) {
            try {
                Dnd.setOn(ctx, true)
                val seen = ask("Режим «Тася-пробник» виден? (шторка, Настройки → Режимы и процедуры)", listOf("Виден как наш режим", "Виден только «Не беспокоить»", "Не виден"))
                Dnd.setOn(ctx, false)
                val off = ask("Режим выключился?", listOf("Да", "Нет"))
                done++
                if (seen == "Не виден" || off == "Нет") bad++
                if (step("Выключение в срок: режим включится и снимется будильником через 5 минут. Выключите экран и ждите.")) {
                    val planned = System.currentTimeMillis() + 5 * 60_000
                    Dnd.setOn(ctx, true)
                    log("dnd_schedule", Dnd.scheduleOff(ctx, planned))
                    tell("Выключите экран и подождите больше 5 минут. Потом разблокируйте и нажмите «Готово».")
                    val last = Dnd.offLog(ctx).takeIf { it.exists() }?.readLines()?.lastOrNull()?.let { JSONObject(it) }
                    if (last != null && last.getLong("planned") == planned) {
                        metric("dnd_off_late_ms", last.getLong("late_ms"))
                        log("dnd_off", last.toString())
                        if (last.getLong("late_ms") > 60_000 || last.optString("error").isNotEmpty()) bad++
                    } else {
                        log("dnd_off", "будильник не сработал — режим ещё включён? фильтр=${Dnd.filter(ctx)}")
                        bad++
                        Dnd.setOn(ctx, false)
                    }
                }
            } catch (e: Exception) {
                error(e, "dnd")
                bad++
            } finally {
                runCatching { Dnd.remove(ctx) }
            }
        }

        if (step("Медиа: включите музыку в плеере и вернитесь сюда.")) {
            val am = ctx.getSystemService(AudioManager::class.java)
            val player = ask("Какой плеер?", listOf("Samsung Music", "YouTube Music", "Spotify", "Telegram", "Другой"))
            log("media_player", player)
            for ((code, name) in listOf(KeyEvent.KEYCODE_MEDIA_PAUSE to "пауза", KeyEvent.KEYCODE_MEDIA_PLAY to "играть", KeyEvent.KEYCODE_MEDIA_NEXT to "следующий")) {
                val before = am.isMusicActive
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                delay(1200)
                log("media_key", "$name: isMusicActive $before → ${am.isMusicActive}")
                if (ask("«$name» сработало?", listOf("Да", "Нет")) == "Нет") bad++
            }
            // Аудиофокус как у листа во время записи: GAIN_TRANSIENT_EXCLUSIVE.
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            val res = am.requestAudioFocus(req)
            delay(800)
            log("focus", "request=$res, музыка=${am.isMusicActive}")
            val paused = ask("Музыка притихла или встала, пока фокус у нас?", listOf("Встала", "Притихла", "Не изменилась"))
            am.abandonAudioFocusRequest(req)
            delay(3000)
            log("focus", "после abandon музыка=${am.isMusicActive}")
            val resumed = ask("После того как отпустили фокус — плеер продолжил сам?", listOf("Да", "Нет"))
            log("media_focus", "$player: фокус → $paused, после → продолжил=$resumed")
            done++
        }

        if (step("Календарь: вставить тестовое событие через час?")) {
            if (!granted(ctx, Manifest.permission.WRITE_CALENDAR) || !granted(ctx, Manifest.permission.READ_CALENDAR)) {
                log("calendar", "нет разрешения — дайте в настройках приложения")
            } else {
                val cal = pickWritableCalendar(calendars())
                val id = cal?.let { insertEvent(it, "Пробник Таси П21") }
                if (id != null) {
                    val seen = ask("Событие «Пробник Таси П21» через час — где видно?", listOf("В обоих", "Только Samsung Calendar", "Только Google Calendar", "Нигде"))
                    deleteEvent(id)
                    val gone = ask("Удалили. Пропало из календарей?", listOf("Да", "Нет"))
                    done++
                    if (seen == "Нигде" || gone == "Нет") bad++
                } else bad++
            }
        }

        summary("шагов сделано: $done, с проблемами: $bad — подробности в логе")
        return when {
            done == 0 -> Status.PARTIAL
            bad > 0 -> Status.FAIL
            else -> Status.PASS
        }
    }
}
