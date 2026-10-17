package ru.poneslas.tasya.probe.entry

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import ru.poneslas.tasya.probe.core.Probe
import ru.poneslas.tasya.probe.core.ProbeScope
import ru.poneslas.tasya.probe.core.Status
import ru.poneslas.tasya.probe.core.fmt
import ru.poneslas.tasya.probe.core.median

/**
 * П11б: свои режимы пользователя → динамические ярлыки (Продукт 5.17: «ярлыки включённых режимов»).
 * Сколько из них показывает One UI Home, доходит ли режим до листа, закрепление на рабочем столе,
 * исчезает ли ярлык после removeDynamicShortcuts. Лимит лаунчера Samsung не документирован [?].
 */
object DynamicShortcutsProbe : Probe(
    "П11б", "Динамические ярлыки своих режимов и закрепление",
    "5 динамических ярлыков → сколько видно в меню иконки One UI Home; режим доходит до листа; requestPinShortcut; выключенный режим — ярлык пропадает.",
    needsHuman = true,
    steps = listOf(
        "Пробник публикует 5 динамических ярлыков «Режим 1…5» (плюс 3 статических из П11).",
        "Долгое нажатие на иконку пробника: сколько ярлыков видно? Открыть «Режим 2» и «Режим 5».",
        "Пробник просит закрепить «Режим 3» на рабочем столе; открыть его с рабочего стола.",
        "Пробник удаляет «Режим 1»: пропал ли он из меню и что с закреплённым.",
    ),
) {
    private const val SRC = "dyn_shortcut"

    private fun info(ctx: android.content.Context, i: Int) = ShortcutInfo.Builder(ctx, "mode$i")
        .setShortLabel("Режим $i")
        .setLongLabel("Тася: режим $i")
        .setIcon(Icon.createWithResource(ctx, android.R.drawable.ic_menu_agenda))
        .setIntent(
            Intent(Intent.ACTION_VIEW).setClass(ctx, ShortcutTrampolineActivity::class.java)
                .putExtra("via", "service").putExtra("src", SRC).putExtra("mode", "custom$i"),
        )
        .setRank(i)
        .build()

    override suspend fun ProbeScope.run(): Status {
        val sm = ctx.getSystemService(ShortcutManager::class.java)
        log("limits", "maxShortcutCountPerActivity=${sm.maxShortcutCountPerActivity}, rateLimitingActive=${sm.isRateLimitingActive}, pinSupported=${sm.isRequestPinShortcutSupported}")
        val set = runCatching { sm.setDynamicShortcuts((1..5).map { info(ctx, it) }) }
            .getOrElse { error(it, "setDynamicShortcuts"); false }
        log("dynamic", "setDynamicShortcuts=$set, опубликовано ${sm.dynamicShortcuts.size}")
        val lines = mutableListOf<String>()

        val seen = ask("Долгое нажатие на иконку «Пробник Таси»: сколько ярлыков «Режим N» видно?", listOf("0", "1", "2", "3", "4", "5"))
        metric("dynamic_visible", seen.toInt())
        lines += "видно динамических: $seen из 5 (лимит API ${sm.maxShortcutCountPerActivity}, вместе со статическими)"

        val opens = collectEntries(setOf(SRC), "Откройте из этого меню «Режим 2» и (если виден) «Режим 5», закрывая лист. Потом «Готово».", 2)
        val modesOk = opens.map { it.mode }.filter { it?.startsWith("custom") == true }
        val times = opens.mapNotNull { it.entryToFrame()?.toDouble() }
        times.forEach { metric("entry_to_frame_ms", it) }
        lines += "открытий ${opens.size}, режимы ${modesOk.joinToString()}" + if (times.isNotEmpty()) ", медиана ${fmt(median(times))} мс" else ""

        var pinned = "не поддерживается"
        if (sm.isRequestPinShortcutSupported) {
            val ok = runCatching { sm.requestPinShortcut(info(ctx, 3), null) }.getOrElse { error(it, "requestPinShortcut"); false }
            log("pin", "requestPinShortcut=$ok")
            pinned = ask("Появилось окно «Добавить на главный экран»? Добавьте и откройте «Режим 3» с рабочего стола.", listOf("Добавлен и открылся", "Добавлен, не открылся", "Окна не было"))
            lines += "закрепление: $pinned"
        }

        sm.removeDynamicShortcuts(listOf("mode1"))
        log("remove", "mode1 убран, осталось ${sm.dynamicShortcuts.size}")
        val gone = ask("Снова долгое нажатие на иконку: «Режим 1» пропал?", listOf("Пропал", "Остался"))
        lines += "после удаления режима: $gone"
        // Закреплённый «Режим 3» при выключении режима надо отключать отдельно: disableShortcuts — проверим, что он серый.
        if (pinned.startsWith("Добавлен")) {
            sm.disableShortcuts(listOf("mode3"), "Режим выключен")
            val grey = ask("Закреплённый «Режим 3» стал неактивным (серым, с подписью «Режим выключен» при нажатии)?", listOf("Да", "Нет"))
            lines += "отключение закреплённого: $grey"
        }
        sm.removeAllDynamicShortcuts()
        sm.enableShortcuts(listOf("mode3"))

        summary(lines.joinToString("; "))
        return when {
            seen.toInt() >= 4 && modesOk.isNotEmpty() && gone == "Пропал" -> Status.PASS
            modesOk.isNotEmpty() -> Status.PARTIAL
            else -> Status.FAIL
        }
    }
}
