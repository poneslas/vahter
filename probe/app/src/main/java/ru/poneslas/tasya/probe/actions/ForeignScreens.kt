package ru.poneslas.tasya.probe.actions

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.AlarmClock
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import ru.poneslas.tasya.probe.assist.Sheet
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.Status
import java.io.File

/** Цель П22: как собрать интент. null — на телефоне нечему его принять (или не видно без <queries>). */
private class Target(val key: String, val title: String, val build: (Context) -> Intent?)

private fun resolvable(ctx: Context, i: Intent) = ctx.packageManager.resolveActivity(i, 0) != null

private fun testFile(ctx: Context, name: String): File {
    val dir = File(ctx.cacheDir, "p22").apply { mkdirs() }
    val f = File(dir, name)
    if (name.endsWith(".pdf")) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(300, 200, 1).create())
        page.canvas.drawText("Пробник Таси, П22", 20f, 100f, Paint().apply { textSize = 18f })
        doc.finishPage(page)
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
    } else {
        f.writeText("Пробник Таси, П22: ACTION_VIEW файла через FileProvider.\n")
    }
    return f
}

private fun viewFile(ctx: Context, name: String, mime: String): Intent {
    val uri = FileProvider.getUriForFile(ctx, "ru.poneslas.tasya.probe.files", testFile(ctx, name))
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun sendTo(pkg: String): (Context) -> Intent? = { ctx ->
    // ACTION_SEND без <queries> не видит чужие пакеты (Android 11+) — тогда честно null [?].
    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Пробник Таси, П22").setPackage(pkg)
        .takeIf { resolvable(ctx, it) }
}

private val targets = listOf(
    Target("dial", "Звонилка (ACTION_DIAL)") { Intent(Intent.ACTION_DIAL, Uri.parse("tel:+70000000000")) },
    Target("sms", "SMS (SENDTO smsto:)") {
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:+70000000000")).putExtra("sms_body", "Пробник Таси, П22")
    },
    Target("share", "«Поделиться» (chooser)") {
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Пробник Таси, П22"), "Поделиться")
    },
    Target("telegram", "Telegram (ACTION_SEND + setPackage)", sendTo("org.telegram.messenger")),
    Target("whatsapp", "WhatsApp (ACTION_SEND + setPackage)", sendTo("com.whatsapp")),
    Target("view_txt", "Открыть .txt (ACTION_VIEW, FileProvider)") { viewFile(it, "probe.txt", "text/plain") },
    Target("view_pdf", "Открыть .pdf (ACTION_VIEW, FileProvider)") { viewFile(it, "probe.pdf", "application/pdf") },
    Target("show_alarms", "«Часы»: будильники (SHOW_ALARMS)") { Intent(AlarmClock.ACTION_SHOW_ALARMS) },
    Target("show_timers", "«Часы»: таймеры (SHOW_TIMERS)") { Intent(AlarmClock.ACTION_SHOW_TIMERS) },
    Target("app_open", "Открыть приложение (getLaunchIntentForPackage «Часов»)") { ctx ->
        // Пакет виден только потому, что SHOW_ALARMS есть в <queries>; любой другой — [?].
        ctx.packageManager.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)?.activityInfo?.packageName
            ?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
    },
)

/** Способ запуска из листа. */
private enum class Way(val label: String) {
    DIRECT("напрямую при открытом листе"),
    AFTER_HIDE("после hide()"),
    ASSISTANT("startAssistantActivity"),
}

object ForeignScreensProbe : Probe(
    "П22", "Чужие экраны из листа: звонилка, SMS, мессенджеры, файл, «Часы»",
    "Каждый интент × способ запуска из листа: открылся ли чужой экран сверху, не ушёл ли под лист.",
    needsHuman = true,
    steps = listOf(
        "Пробник открывает лист и запускает чужой экран одним из трёх способов.",
        "Посмотрите, что видно, вернитесь в пробник (через «Недавние») и ответьте — вопрос будет и в листе, и на экране проверки.",
        "В конце — то же на заблокированном экране и таймер «Часов» с блокировки (П17 на блокировке).",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        val waysAnswer = ask("Какие способы запуска проверяем?", listOf("Все три", "Только напрямую", "Только startAssistantActivity"))
        val ways = when (waysAnswer) {
            "Только напрямую" -> listOf(Way.DIRECT)
            "Только startAssistantActivity" -> listOf(Way.ASSISTANT)
            else -> Way.entries
        }
        val onTop = mutableMapOf<Way, Int>()
        val tried = mutableMapOf<Way, Int>()
        val notOk = mutableListOf<String>()
        var stop = false

        for (t in targets) {
            if (stop) break
            val probeIntent = t.build(ctx)
            if (probeIntent == null || !resolvable(ctx, probeIntent)) {
                log("skip", "${t.title}: нечему принять или не видно без <queries>")
                continue
            }
            for (w in ways) {
                val ans = launchAndAsk(t, w)
                if (ans == null) {
                    stop = true
                    break
                }
                tried[w] = (tried[w] ?: 0) + 1
                if (ans == "Экран сверху") onTop[w] = (onTop[w] ?: 0) + 1 else notOk += "${t.key}/${w.name.lowercase()}: $ans"
            }
        }
        ways.forEach { w -> metric("on_top_${w.name.lowercase()}", onTop[w] ?: 0); metric("tried_${w.name.lowercase()}", tried[w] ?: 0) }

        if (step("На заблокированном экране: звонилка и таймеры «Часов» из листа?")) {
            for (key in listOf("dial", "show_timers")) {
                val t = targets.first { it.key == key }
                if (waitLockedOpen("Заблокируйте экран и откройте лист боковой кнопкой (${t.title}).") == null) continue
                launch(t, Way.ASSISTANT)
                val a = ask("${t.title} с блокировки: что было?", listOf("Попросил разблокировать и открыл", "Открылся поверх блокировки", "Ничего"))
                if (a == "Ничего") notOk += "$key/locked: ничего"
            }
        }
        if (step("П17 на блокировке: таймер на 1 минуту с EXTRA_SKIP_UI при заблокированном экране?")) {
            if (waitLockedOpen("Заблокируйте экран и откройте лист боковой кнопкой.") != null) {
                val am = ctx.getSystemService(AlarmManager::class.java)
                val before = am.nextAlarmClock?.triggerTime
                val timer = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, 60).putExtra(AlarmClock.EXTRA_MESSAGE, "Пробник П22")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(timer)
                } catch (e: Exception) {
                    error(e, "SET_TIMER locked")
                }
                delay(2000)
                // Таймеры в getNextAlarmClock обычно не попадают [?] — пишем для сравнения.
                log("timer_locked", "nextAlarmClock до=$before после=${am.nextAlarmClock?.triggerTime}")
                val a = ask("Таймер на 1 минуту пошёл?", listOf("Да, без разблокировки", "Попросил разблокировать", "Нет"))
                if (a == "Нет") notOk += "timer/locked: нет"
                runCatching {
                    ctx.startActivity(Intent(AlarmClock.ACTION_DISMISS_TIMER).putExtra(AlarmClock.EXTRA_MESSAGE, "Пробник П22").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { error(it, "DISMISS_TIMER") }
                ask("Сняли таймер через DISMISS_TIMER. Пропал? Если нет — снимите руками.", listOf("Пропал", "Остался"))
            }
        }

        summary(buildString {
            ways.forEach { w -> append("${w.label}: сверху ${onTop[w] ?: 0} из ${tried[w] ?: 0}; ") }
            if (notOk.isNotEmpty()) append("проблемы: ${notOk.joinToString()}")
        })
        val anyWayAll = ways.any { (tried[it] ?: 0) > 0 && onTop[it] == tried[it] }
        return when {
            tried.values.sum() == 0 -> Status.PARTIAL
            anyWayAll && notOk.none { it.contains("locked") } -> Status.PASS
            anyWayAll || onTop.values.sum() > 0 -> Status.PARTIAL
            else -> Status.FAIL
        }
    }

    /** Открыть лист, запустить, спросить. null — человек сказал «Хватит». */
    private suspend fun ProbeScope.launchAndAsk(t: Target, w: Way): String? {
        val open = Sheet.open()
        if (open == null) {
            log("sheet", "лист не открылся перед ${t.key}")
            return "лист не открылся"
        }
        launch(t, w)
        delay(1500)
        log("after", "${t.key}/${w.name}: лист виден=${Sheet.visible.value}")
        val a = ask(
            "${t.title}, ${w.label}: что видно? (вернитесь в пробник, чтобы ответить)",
            listOf("Экран сверху", "Под листом", "Ничего не открылось", "Хватит"),
        )
        Sheet.close()
        delay(500)
        return a.takeIf { it != "Хватит" }
    }

    private suspend fun ProbeScope.launch(t: Target, w: Way) {
        val intent = t.build(ctx) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            when (w) {
                Way.DIRECT -> ctx.startActivity(intent)
                Way.AFTER_HIDE -> {
                    Sheet.close()
                    ctx.startActivity(intent)
                }
                Way.ASSISTANT -> {
                    val s = Sheet.session.value
                    if (s == null || !Sheet.visible.value) log("launch", "${t.key}: лист не показан — startAssistantActivity не сработает")
                    s?.startAssistantActivity(intent)
                }
            }
            log("launch", "${t.key}/${w.name}: без исключения")
        } catch (e: Exception) {
            error(e, "${t.key}/${w.name}")
        }
    }
}
