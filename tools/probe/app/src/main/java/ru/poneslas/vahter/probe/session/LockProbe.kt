package ru.poneslas.vahter.probe.session

import android.app.KeyguardManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import ru.poneslas.vahter.probe.assist.LockEvents
import ru.poneslas.vahter.probe.assist.OpenRecord
import ru.poneslas.vahter.probe.assist.Sheet
import ru.poneslas.vahter.probe.core.Probe
import ru.poneslas.vahter.probe.core.ProbeScope
import ru.poneslas.vahter.probe.core.Status

/** П5: лист поверх блокировки, микрофон там же, разблокировка своей активностью. */
object LockProbe : Probe(
    "П5", "Поверх блокировки: лист, микрофон, разблокировка",
    "Лист открывается на заблокированном экране, микрофон пишет, «Разблокировать» снимает блокировку и возвращает лист.",
    needsHuman = true,
    steps = listOf(
        "Заблокируйте телефон и удержите боковую кнопку (эмулятор: KEYCODE_SLEEP, KEYCODE_WAKEUP, затем KEYCODE_ASSIST).",
        "В листе: «Записать» и скажите фразу.",
        "В листе: «Разблокировать (П5)» → отпечаток/PIN → лист должен вернуться.",
        "Откройте лист и нажмите кнопку питания — лист должен скрыться (onLockscreenShown).",
        "Двойное нажатие боковой кнопки на блокировке — камера Samsung, не наш лист.",
    ),
) {
    override suspend fun ProbeScope.run(): Status {
        val km = ctx.getSystemService(KeyguardManager::class.java)
        log("keyguard", "locked=${km.isKeyguardLocked} secure=${km.isDeviceSecure}")
        if (!km.isDeviceSecure) log("warn", "на устройстве нет PIN/отпечатка — разблокировка пройдёт без проверки")
        Sheet.close()

        // 1. Лист на блокировке
        val lockedOpen: OpenRecord? = waitOrHuman("Заблокируйте экран и удержите боковую кнопку. Жду открытия листа на блокировке (до 2 мин).", "Не открывается") {
            Sheet.opens.first { it.locked }
        }
        if (lockedOpen == null) {
            summary("Лист на блокировке не открылся")
            return Status.FAIL
        }
        log("locked_open", lockedOpen.describe())
        metric("locked_prepare_to_frame_ms", lockedOpen.prepareToFrame)

        // 2. Микрофон на блокировке
        var micOk = false
        if (ask("Лист на блокировке. Записать 3 с? Скажите фразу после нажатия.", listOf("Записать", "Пропустить")) == "Записать") {
            val st = recordFor(ctx, 3000)
            log("rec_locked", st.describe())
            micOk = st.ok && st.zeroFraction < 0.99 && st.silenced != true
            metric("locked_rms", st.rms)
        }

        // 3. Разблокировка
        val unlock = waitOrHuman("Нажмите в листе «Разблокировать (П5)» и подтвердите отпечатком/PIN.", "Кнопка не сработала") {
            UnlockBus.events.first()
        }
        var unlockOk = false
        if (unlock == null) {
            log("unlock", "нет события")
        } else {
            log("unlock", "${unlock.result}, активность через ${unlock.tCreate - unlock.tAction} мс, итог через ${unlock.tResult - unlock.tAction} мс, было заблокировано=${unlock.wasLocked}")
            if (unlock.tCreate > 0) metric("unlock_activity_ms", unlock.tCreate - unlock.tAction)
            metric("unlock_result_ms", unlock.tResult - unlock.tAction)
            if (unlock.result == "succeeded") {
                val back = withTimeoutOrNull(3000) { Sheet.opens.first() }
                log("sheet_after_unlock", back?.describe() ?: "лист не вернулся за 3 с")
                back?.let { metric("unlock_to_sheet_ms", it.tFrame - unlock.tResult) }
                unlockOk = back != null
            }
        }

        // 4. Блокировка при открытом листе
        Sheet.close()
        delay(300)
        val lockShown = waitOrHuman("Откройте лист (кнопкой или «Открыть лист»), затем нажмите кнопку питания. Если лист не скрылся — «Не пришло».", "Не пришло") {
            LockEvents.lockscreenShown.first()
        }
        delay(300)
        log("lockscreen_shown", if (lockShown != null) "пришёл onLockscreenShown, лист скрыт=${!Sheet.visible.value}" else "onLockscreenShown не пришёл")

        // 5. Двойное нажатие
        val dbl = ask("Заблокируйте и дважды нажмите боковую кнопку. Открылась камера Samsung, а наш лист — нет?", listOf("Да", "Открылся наш лист", "Ничего", "Пропустить"))

        summary("лист на блокировке: да (${lockedOpen.prepareToFrame} мс); микрофон: ${if (micOk) "пишет" else "нет/пропущено"}; разблокировка: ${unlock?.result ?: "нет"}, лист вернулся: $unlockOk; onLockscreenShown: ${lockShown != null}; двойное нажатие: $dbl")
        return when {
            micOk && unlockOk -> Status.PASS
            else -> Status.PARTIAL
        }
    }
}

/**
 * Ждёт событие [wait], пока человек не нажал «сдаюсь» и не прошло [timeoutMs].
 * Ожидание стартует до показа вопроса — событие не теряется.
 */
internal suspend fun <T> ProbeScope.waitOrHuman(question: String, giveUp: String, timeoutMs: Long = 120_000, wait: suspend () -> T): T? = coroutineScope {
    val ev = async { wait() }
    val human = async { ask(question, listOf(giveUp)) }
    withTimeoutOrNull(timeoutMs) { while (!human.isCompleted && !ev.isCompleted) delay(100) }
    val r = if (ev.isCompleted) ev.await() else null
    ev.cancel()
    human.cancel()
    r
}
