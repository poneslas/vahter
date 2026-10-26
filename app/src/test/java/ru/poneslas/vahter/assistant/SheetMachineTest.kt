package ru.poneslas.vahter.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Схема состояний листа «солнышко» (Capture.kt): переходы записи и правило «солнышко или поле». */
class SheetMachineTest {
    private fun run(vararg evs: Ev, from: RecState = RecState()): Pair<RecState, List<Effect>> {
        var s = from; val effs = mutableListOf<Effect>()
        evs.forEach { e -> val (n, eff) = Machine.reduce(s, e); s = n; eff?.let { effs += it } }
        return s to effs
    }

    @Test fun `держал и отпустил — уходит`() {
        val (s, e) = run(Ev.Down(true), Ev.Up(900, moved = false))
        assertEquals(Rec.RECOG, s.rec)
        assertEquals(listOf(Effect.Start(false), Effect.Stop(How.SEND)), e)
    }

    @Test fun `короткое нажатие — без рук, запись идёт дальше`() {
        val (s, e) = run(Ev.Down(true), Ev.Up(120, moved = false))
        assertEquals(Rec.FREE, s.rec)
        assertEquals(listOf<Effect>(Effect.Start(false)), e)
    }

    @Test fun `держал и утащил влево — отмена`() {
        val (s, e) = run(Ev.Down(true), Ev.Drag(-40f), Ev.Drag(-75f))
        assertEquals(Rec.IDLE, s.rec)
        assertEquals(Effect.Stop(How.CANCEL), e.last())
    }

    @Test fun `держал и утащил вправо — правка`() {
        val (s, e) = run(Ev.Down(true), Ev.Drag(75f))
        assertEquals(Rec.RECOG, s.rec)
        assertEquals(Effect.Stop(How.EDIT), e.last())
    }

    @Test fun `цели подсвечиваются на подходе`() {
        val (s, _) = run(Ev.Down(true), Ev.Drag(45f))
        assertTrue(s.nearEdit); assertFalse(s.nearCancel)
        val (s2, _) = run(Ev.Down(true), Ev.Drag(-45f))
        assertTrue(s2.nearCancel); assertFalse(s2.nearEdit)
    }

    // Баг 26.10: «Что это?» — микрофон без рук, тащить в ✎ и в ✕ не получалось.
    @Test fun `без рук — тоже можно утащить в правку и в отмену`() {
        val (free, _) = run(Ev.Free(true))
        assertEquals(Rec.FREE, free.rec)
        assertEquals(Effect.Stop(How.EDIT), run(Ev.Down(true), Ev.Drag(80f), from = free).second.last())
        assertEquals(Effect.Stop(How.CANCEL), run(Ev.Down(true), Ev.Drag(-80f), from = free).second.last())
    }

    @Test fun `без рук — нажатие отправляет, сдвиг без цели — ничего`() {
        val (free, _) = run(Ev.Free(true))
        assertEquals(Effect.Stop(How.SEND), run(Ev.Down(true), Ev.Up(80, moved = false), from = free).second.last())
        val (s, e) = run(Ev.Down(true), Ev.Drag(20f), Ev.Up(400, moved = true), from = free)
        assertEquals(Rec.FREE, s.rec); assertTrue(e.isEmpty()); assertEquals(0f, s.x)
    }

    @Test fun `без рук — кнопки ✕ и ✎`() {
        val (free, _) = run(Ev.Free(true))
        assertEquals(Effect.Stop(How.CANCEL), run(Ev.TapCancel, from = free).second.single())
        assertEquals(Effect.Stop(How.EDIT), run(Ev.TapEdit, from = free).second.single())
    }

    @Test fun `в камере правки нет — вправо не утащить`() {
        val (s, e) = run(Ev.Down(false), Ev.Drag(120f))
        assertEquals(Rec.HOLD, s.rec); assertEquals(0f, s.x); assertFalse(s.nearEdit)
        assertEquals(listOf<Effect>(Effect.Start(false)), e)
        assertTrue(run(Ev.TapEdit, from = s).second.isEmpty())
    }

    @Test fun `смена режима выбрасывает запись`() {
        val (s, e) = run(Ev.Down(true), Ev.ModeChanged)
        assertEquals(Rec.IDLE, s.rec); assertEquals(Effect.Stop(How.CANCEL), e.last())
        assertTrue(run(Ev.ModeChanged).second.isEmpty())
    }

    @Test fun `пока распознаёт — нажатия не считаются`() {
        val (recog, _) = run(Ev.Down(true), Ev.Up(900, false))
        val (s, e) = run(Ev.Down(true), Ev.Drag(-90f), Ev.Up(100, false), from = recog)
        assertEquals(Rec.RECOG, s.rec); assertTrue(e.isEmpty())
        assertEquals(Rec.IDLE, run(Ev.Done, from = s).first.rec)
    }

    @Test fun `предел записи — отдать, что есть`() {
        assertEquals(Effect.Stop(How.SEND), run(Ev.Free(true), Ev.Timeout).second.last())
        assertNull(Machine.reduce(RecState(), Ev.Timeout).second)
    }

    @Test fun `запоздалый сдвиг после отпускания ничего не делает`() {
        val (free, _) = run(Ev.Down(true), Ev.Up(100, false))
        val (s, e) = run(Ev.Drag(80f), from = free)
        assertEquals(Rec.FREE, s.rec); assertTrue(e.isEmpty())
    }

    // Микрофон фото-режима у правого края: до ✎ меньше 70 dp — порог ближе, иначе правка недостижима.
    @Test fun `у края экрана правка ближе`() {
        assertEquals(50f, editRoom(64f)); assertEquals(Gesture.EDIT_DP, editRoom(300f)); assertEquals(36f, editRoom(20f))
        val (s, e) = run(Ev.Down(true, editAt = 50f), Ev.Drag(30f))
        assertTrue(s.nearEdit)
        assertEquals(Effect.Stop(How.EDIT), run(Ev.Drag(55f), from = s).second.single())
        assertTrue(e.size == 1)
    }

    // Баг 26.10: «Что это?» → свайп → Ассистент: мелькало поле. Правило одно и не зависит от пути.
    @Test fun `солнышко или поле`() {
        assertFalse(SurfaceRules.field("", opened = false, voiceReady = true, acceptsVoice = true, photoMain = false))   // Ассистент — солнышко
        assertFalse(SurfaceRules.field("", opened = false, voiceReady = true, acceptsVoice = true, photoMain = true))    // «Что это?» — солнышко-камера
        assertTrue(SurfaceRules.field("правка", opened = false, voiceReady = true, acceptsVoice = true, photoMain = false))
        assertTrue(SurfaceRules.field("", opened = true, voiceReady = true, acceptsVoice = true, photoMain = false))
        assertTrue(SurfaceRules.field("", opened = false, voiceReady = false, acceptsVoice = true, photoMain = false))   // голоса нет — поле
        assertFalse(SurfaceRules.field("", opened = false, voiceReady = false, acceptsVoice = true, photoMain = true))   // камера работает и без голоса
        assertFalse(SurfaceRules.field("  ", opened = false, voiceReady = true, acceptsVoice = true, photoMain = false)) // пробелы — не текст
    }
}
