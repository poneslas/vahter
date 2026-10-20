package ru.poneslas.vahter.actions

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * Действия на телефоне через интенты «Часов» (класс D: отмена не гарантирована, Архитектура 7).
 * Проверено пробником: Google DeskClock принимает все шесть интентов; Samsung Clock — П17.
 */
object PhoneActions {

    fun setTimer(context: Context, seconds: Int, label: String? = null): Result<Unit> = runCatching {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        context.startActivity(intent)
    }

    fun setAlarm(context: Context, hour: Int, minute: Int, label: String? = null): Result<Unit> = runCatching {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        context.startActivity(intent)
    }

    fun showTimers(context: Context) = runCatching {
        context.startActivity(Intent(AlarmClock.ACTION_SHOW_TIMERS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun showAlarms(context: Context) = runCatching {
        context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Локальные правила: короткие точные команды, без сети и без мозга (Архитектура, 5). */
object Rules {
    sealed interface Match {
        data class Timer(val seconds: Int, val title: String) : Match
        data class Alarm(val hour: Int, val minute: Int) : Match
        data class Torch(val on: Boolean) : Match
    }

    private val numbers = mapOf(
        "одну" to 1, "одна" to 1, "один" to 1, "две" to 2, "два" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10, "пятнадцать" to 15,
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40,
    )

    private fun number(word: String): Int? = word.toIntOrNull() ?: numbers[word]

    /** «Начни заново», «забудь разговор», «очисти чат» — стереть разговор режима (с подтверждением). */
    fun isRestart(phrase: String): Boolean {
        val w = phrase.lowercase().replace('ё', 'е').replace(Regex("[.,!?]"), " ").trim()
        if (w.split(Regex("\\s+")).size > 5) return false
        return Regex("(начн(и|ем)|давай) (все |разговор )?(заново|сначала|с начала)|(забудь|сотри|очисти|удали) (этот |весь |наш )?(разговор|чат|переписку|историю|контекст)|новый (разговор|чат)").containsMatchIn(w)
    }

    /** Связки между просьбами: «и», «а ещё», «потом», «затем», «плюс», запятая, точка с запятой. */
    private val joiner = Regex("(?iu)\\s*(?:[,;]\\s*(?:а потом|а затем|а ещё|а еще|а также|и потом|и ещё|и еще|потом|затем|и|а)?|\\s(?:а потом|а затем|а ещё|а еще|а также|и потом|и ещё|и еще|потом|затем|плюс|и)\\s)\\s*")

    /** Части составной фразы; одна часть — фраза одиночная (Бэклог №19). */
    fun parts(phrase: String): List<String> = phrase.split(joiner).map { it.trim(' ', '.', '!', '?') }.filter { it.isNotBlank() }

    /** В фразе больше одной просьбы: по связке или по двум разным действиям — такую фразу локальное правило не берёт. */
    fun isCompound(phrase: String): Boolean {
        val w = phrase.lowercase()
        val kinds = listOf("таймер", "будильник", "фонар").count { w.contains(it) }
        return kinds > 1 || parts(phrase).size > 1
    }

    fun match(phrase: String): Match? {
        val words = phrase.lowercase().replace(Regex("[.,!?]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        // «включи / выключи фонарик» — коротко и без вариантов
        if (words.size <= 3 && words.any { it.startsWith("фонар") }) {
            if (words.any { it.startsWith("выкл") || it == "погаси" || it == "убери" }) return Match.Torch(false)
            if (words.any { it.startsWith("вкл") || it == "зажги" || it == "посвети" } || words.size == 1) return Match.Torch(true)
        }
        if ("таймер" in words) {
            val i = words.indexOfFirst { it.startsWith("минут") || it.startsWith("секунд") || it.startsWith("час") }
            if (i > 0) {
                val n = number(words[i - 1]) ?: return null
                val unit = words[i]
                val seconds = when {
                    unit.startsWith("секунд") -> n
                    unit.startsWith("час") -> n * 3600
                    else -> n * 60
                }
                val title = when {
                    unit.startsWith("секунд") -> "0:%02d".format(n)
                    unit.startsWith("час") -> "$n:00:00"
                    else -> "$n:00"
                }
                return Match.Timer(seconds, title)
            }
        }
        if ("будильник" in words) {
            val na = words.indexOf("на")
            if (na >= 0 && na + 1 < words.size) {
                val t = words[na + 1]
                Regex("^(\\d{1,2})[:.](\\d{2})$").find(t)?.let { return Match.Alarm(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
                number(t)?.takeIf { it in 0..23 }?.let { return Match.Alarm(it, 0) }
            }
        }
        return null
    }
}
