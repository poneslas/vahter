package ru.poneslas.vahter.probe.actions

import android.app.Activity
import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.Condition
import android.util.Log
import androidx.core.content.edit
import org.json.JSONObject
import ru.poneslas.vahter.probe.TAG
import java.io.File

/**
 * «Не беспокоить» через своё AutomaticZenRule (dnd.set, С8). Правило одно, id хранится в prefs.
 * С targetSdk ≥ 35 приложение управляет своими правилами без доступа к политике уведомлений [?] —
 * проверка как раз это и выясняет.
 */
object Dnd {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("probes", Context.MODE_PRIVATE)
    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
    private fun conditionId(ctx: Context) = Condition.newId(ctx).appendPath("probe").build()

    /** Создаёт правило, если его нет. Возвращает id или бросает исключение системы. */
    fun ensureRule(ctx: Context): String {
        val nm = nm(ctx)
        prefs(ctx).getString("dnd_rule", null)?.let { if (nm.getAutomaticZenRule(it) != null) return it }
        val rule = AutomaticZenRule.Builder("Вахтёр-пробник", conditionId(ctx))
            .setType(AutomaticZenRule.TYPE_OTHER)
            .setConfigurationActivity(ComponentName(ctx, DndConfigActivity::class.java))
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setTriggerDescription("Пробник Вахтёра, П21")
            .setManualInvocationAllowed(true)
            .setEnabled(true)
            .build()
        val id = nm.addAutomaticZenRule(rule)
        prefs(ctx).edit { putString("dnd_rule", id) }
        return id
    }

    fun setOn(ctx: Context, on: Boolean, source: Int = Condition.SOURCE_USER_ACTION) {
        val id = ensureRule(ctx)
        val state = if (on) Condition.STATE_TRUE else Condition.STATE_FALSE
        nm(ctx).setAutomaticZenRuleState(id, Condition(conditionId(ctx), if (on) "Вахтёр: тишина" else "выкл", state, source))
    }

    /** Убрать правило совсем — чтобы в «Режимах» One UI не остался мусор. */
    fun remove(ctx: Context) {
        val id = prefs(ctx).getString("dnd_rule", null) ?: return
        runCatching { nm(ctx).removeAutomaticZenRule(id) }
        prefs(ctx).edit { remove("dnd_rule") }
    }

    fun filter(ctx: Context) = nm(ctx).currentInterruptionFilter

    /**
     * Снять режим в [atWall]: точный будильник, если разрешён, иначе неточный.
     * Возвращает, какой способ выбран.
     */
    fun scheduleOff(ctx: Context, atWall: Long): String {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 21, Intent(ctx, DndOffReceiver::class.java).putExtra("planned", atWall),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, pi)
            "setExactAndAllowWhileIdle"
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, pi)
            "setAndAllowWhileIdle (точные будильники не разрешены)"
        }
    }

    fun offLog(ctx: Context) = File(ctx.filesDir, "dnd_off.jsonl")
}

/** Снимает режим по будильнику и пишет фактическое время. Процесс к этому моменту может быть новым. */
class DndOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val planned = intent.getLongExtra("planned", 0)
        val actual = System.currentTimeMillis()
        val err = runCatching { Dnd.setOn(context, false, Condition.SOURCE_SCHEDULE) }.exceptionOrNull()
        val o = JSONObject().put("planned", planned).put("actual", actual).put("late_ms", actual - planned)
            .put("filter_after", Dnd.filter(context)).put("error", err?.toString() ?: "")
        Log.i(TAG, "DND off $o")
        Dnd.offLog(context).appendText(o.toString() + "\n")
    }
}

/** Экран настройки правила: обязателен для AutomaticZenRule без ConditionProviderService. */
class DndConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
