package ru.poneslas.vahter.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Бэклог №19: составную фразу локальное правило не забирает. */
class RulesTest {
    @Test fun single() {
        assertFalse(Rules.isCompound("Поставь таймер на 5 минут"))
        assertFalse(Rules.isCompound("будильник на 7:30"))
        assertTrue(Rules.match("Поставь таймер на 5 минут") is Rules.Match.Timer)
    }

    @Test fun compound() {
        assertTrue(Rules.isCompound("Поставь будильник на 7:30 и таймер на одну минуту"))
        assertTrue(Rules.isCompound("таймер на 5 минут, а потом включи фонарик"))
        assertTrue(Rules.isCompound("будильник на 8:00 таймер на 2 минуты"))
        assertEquals(listOf("Поставь будильник на 7:30", "таймер на одну минуту"), Rules.parts("Поставь будильник на 7:30 и таймер на одну минуту"))
        val p = Rules.parts("таймер на 5 минут, а потом позвони маме")
        assertEquals(listOf("таймер на 5 минут", "позвони маме"), p)
        assertTrue(Rules.match(p[0]) is Rules.Match.Timer)
        assertEquals(null, Rules.match(p[1]))
    }
}
