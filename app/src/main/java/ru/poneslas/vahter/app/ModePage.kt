package ru.poneslas.vahter.app

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.drawBehind
import ru.poneslas.vahter.ui.VoiceRow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import ru.poneslas.vahter.ui.MNote
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import ru.poneslas.vahter.R
import ru.poneslas.vahter.core.Modes
import ru.poneslas.vahter.ui.Item
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import ru.poneslas.vahter.tr
import androidx.compose.ui.graphics.Color
import ru.poneslas.vahter.App
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Column
import ru.poneslas.vahter.ui.Note
import ru.poneslas.vahter.ui.BaseText
import ru.poneslas.vahter.ui.MChip
import androidx.compose.material3.Text
import ru.poneslas.vahter.core.Mode
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import ru.poneslas.vahter.ui.Ink
import ru.poneslas.vahter.ui.Sw
import androidx.compose.foundation.layout.Row
import ru.poneslas.vahter.ui.Ic
import androidx.compose.foundation.layout.Arrangement
import android.Manifest
import ru.poneslas.vahter.core.Brains
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import ru.poneslas.vahter.core.Primary
import ru.poneslas.vahter.ui.SetCard
import ru.poneslas.vahter.ui.FormPage
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import ru.poneslas.vahter.ui.TopBar
import ru.poneslas.vahter.ui.TextButton
import ru.poneslas.vahter.ui.Note
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.Activity
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import java.text.BreakIterator

/** Страница режима (ui.js, modePage): название, значок, промпт с диктовкой, подсказки, что принимает, главное, действия, мозг. */
@Composable
fun ModePage(a: MainActivity, id: String) {
    val app = App.instance
    val conv = app.conversation
    val state by conv.state.collectAsState()
    val m = state.modes.firstOrNull { it.id == id } ?: return
    val main = m.id == Modes.MAIN
    // Встроенные режимы только включаются и выключаются (Бэклог №14): остальное — для просмотра.
    val ro = m.builtin
    val ed: ((Mode) -> Mode) -> Unit = { f -> conv.editMode(m.id, f) }
    TopBar(m.title, if (m.builtin) tr(R.string.mode_builtin) else tr(R.string.mode_custom_id), onBack = a::back, titleIcon = Ic.mode(m.icon), titleEmoji = Ic.emoji(m.icon))
    FormPage {
        if (ro) Note(tr(R.string.mode_builtin_note))
        if (!ro) {
            Field(tr(R.string.mode_title), m.title, tr(R.string.mode_title_hint)) { v -> ed { it.copy(title = v) } }
            Column {
                Lh(tr(R.string.mode_icon))
                IconGrid(m.icon) { k -> ed { it.copy(icon = k) } }
            }
        }
        Column {
            // Промпт меняется у всех режимов. У встроенных — с «Вернуть как было»; у Ассистента — «свои правила» поверх служебных.
            Lh(tr(if (main) R.string.mode_rules else R.string.mode_prompt))
            if (main) MNote(tr(R.string.mode_rules_note))
            Area(m.prompt, tr(if (main) R.string.mode_rules_hint else R.string.mode_prompt_hint)) { v -> ed { it.copy(prompt = v, promptEdited = true) } }
            Dictate { text -> ed { it.copy(prompt = (it.prompt.trim() + " " + text).trim(), promptEdited = true) } }
            val def = Modes.builtin.firstOrNull { it.id == m.id }?.prompt
            if (ro && !main && def != null && m.prompt != def) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(tr(R.string.mode_prompt_reset)) { ed { it.copy(prompt = def, promptEdited = false) } }
            }
        }
        if (!ro) {
            Field(tr(R.string.mode_empty_label), m.hint, tr(R.string.mode_empty_hint)) { v -> ed { it.copy(hint = v) } }
            MNote(tr(R.string.mode_empty_note))
            Field(tr(R.string.mode_ph_label), m.placeholder, tr(R.string.mode_whatsthis_ph)) { v -> ed { it.copy(placeholder = v) } }
        }
        Column {
            Lh(tr(R.string.mode_accepts))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MChip(tr(R.string.set_voice), m.accepts.voice, Ic.mic, onClick = if (ro) null else ({ ed { it.copy(accepts = it.accepts.copy(voice = !it.accepts.voice)) } }))
                MChip(tr(R.string.mode_accepts_text), true, Ic.kbd)
                MChip(tr(R.string.set_cap_vision), m.accepts.photo, Ic.cam, onClick = if (ro) null else ({ ed { it.copy(accepts = it.accepts.copy(photo = !it.accepts.photo), primary = if (it.accepts.photo) Primary.VOICE else it.primary) } }))
            }
        }
        if (m.accepts.photo && !main) Column {
            Lh(tr(R.string.mode_primary))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.chipBg).padding(3.dp)) {
                listOf(Primary.VOICE to (tr(R.string.set_voice) to Ic.mic), Primary.PHOTO to (tr(R.string.set_cap_vision) to Ic.cam)).forEach { (v, tl) ->
                    val on = m.primary == v
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Color.White else Color.Transparent)
                        .clickable(enabled = !ro) { ed { it.copy(primary = v) } }.padding(9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(tl.second), null, Modifier.size(16.dp), tint = Ink.ink)
                        Spacer(Modifier.width(6.dp))
                        Text(tl.first, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            MNote(if (m.primary == Primary.PHOTO) tr(R.string.mode_primary_photo_note)
                else tr(R.string.mode_primary_voice_note))
        }
        SetCard {
            if (main) Item(tr(R.string.set_cap_tools), tr(R.string.mode_tools_all), icon = Ic.shield)
            else if (ro) Item(tr(R.string.set_cap_tools), if (m.tools) tr(R.string.mode_tools_on) else tr(R.string.set_notif_off), icon = Ic.shield)
            else Item(tr(R.string.set_cap_tools), if (m.tools) tr(R.string.mode_tools_on) else tr(R.string.mode_tools_off),
                icon = Ic.shield, end = { Sw(m.tools) }) { ed { it.copy(tools = !it.tools) } }
            Item(tr(R.string.mode_lang), tr(R.string.mode_lang_ru), icon = Ic.wave)
        }
        Column {
            // Мозг режима: DeepSeek или Hermes; смена начинает разговор заново (Архитектура 2.2). Фото-режимам — только со зрением; подпись «нужен со зрением» — только если у текущего его нет.
            val hermesOn = App.instance.hermesLink() != null
            SetCard {
                Item(tr(R.string.key_h), Brains.byId(m.brain).title + if (m.accepts.photo && !Brains.byId(m.brain).vision) tr(R.string.mode_brain_needs_vision) else "", icon = Ic.spark,
                    warn = m.brain == Brains.hermes.id && !hermesOn) {
                    val opts = Brains.all.filter { !m.accepts.photo || it.vision }
                        .map { it.id to (it.title + if (it.id == Brains.hermes.id && !hermesOn) " · " + tr(R.string.mode_brain_hermes_off) else "") }
                    a.choose(MainActivity.ChoiceDialog(tr(R.string.mode_brain_pick), opts, m.brain) { v -> conv.setBrain(m.id, v) })
                }
            }
            MNote(tr(R.string.mode_brain_note))
        }
        if (!m.builtin) {
            var ask by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, Color(0xFFE7C2BE), RoundedCornerShape(12.dp))
                .clickable { if (ask) { a.back(); conv.deleteMode(m.id) } else ask = true }.padding(12.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Ic.del), null, Modifier.size(18.dp), tint = Ink.danger)
                Spacer(Modifier.width(8.dp))
                Text(if (ask) tr(R.string.mode_delete_confirm) else tr(R.string.mode_delete), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.danger)
            }
        }
    }
}

/** Два ряда по шесть на типичном экране: 11 значков + последняя ячейка — свой эмодзи (ui.js, MODE_ICONS, iconGrid). */
val MODE_ICONS = listOf("spark", "transl", "lens", "food", "cart", "flight", "book", "home", "work", "heart", "car")
private val EMOJI_QUICK = listOf("🍕", "💊", "🐶", "🌱", "🎓", "⚽", "🧾", "✈️")

/** Сетка значков режима: шесть в ряд; последняя ячейка — свой эмодзи (пунктир и «+»; выбран — встаёт в неё). */
@Composable
private fun IconGrid(current: String, onPick: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val emo = Ic.emoji(current)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (MODE_ICONS.map { it as String? } + listOf(null)).chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    val on = if (k == null) emo != null else current == k
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(if (on) Ink.ink else Color.White)
                        .then(if (k == null && emo == null) Modifier.dashedBorder() else Modifier.border(1.dp, if (on) Ink.ink else Ink.line, RoundedCornerShape(12.dp)))
                        .clickable { if (k == null) picking = !picking else onPick(k) }, contentAlignment = Alignment.Center) {
                        when {
                            k != null -> Icon(painterResource(Ic.mode(k)), k, Modifier.size(22.dp), tint = if (on) Color.White else Ink.soft)
                            emo != null -> Text(emo, fontSize = 22.sp)
                            else -> Text("☺+", fontSize = 15.sp, color = Ink.faint)
                        }
                    }
                }
            }
        }
        if (picking) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.surf).border(1.dp, Ink.line, RoundedCornerShape(16.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var text by remember { mutableStateOf("") }
            OutlinedTextField(text, { v ->
                text = v
                // первая графема — эмодзи, даже если из нескольких символов (флаги, семьи)
                val it = BreakIterator.getCharacterInstance().apply { setText(v.trim()) }
                val end = it.next(); if (v.isNotBlank() && end > 0) { onPick("emoji:" + v.trim().substring(0, end)); picking = false }
            }, Modifier.fillMaxWidth(), placeholder = { Text(tr(R.string.mode_emoji_hint)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                EMOJI_QUICK.forEach { e ->
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF1F2EE)).clickable { onPick("emoji:$e"); picking = false }.padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center) { Text(e, fontSize = 20.sp) }
                }
            }
        }
    }
}

/** Пунктирная рамка ячейки «свой эмодзи». */
private fun Modifier.dashedBorder() = drawBehind {
    drawRoundRect(Ink.faint, cornerRadius = CornerRadius(12.dp.toPx()),
        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
}

/** .mform .lh */
@Composable
private fun Lh(t: String) = Text(t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.serverD, modifier = Modifier.padding(vertical = 4.dp))

/** .fl: подпись 12.5/600 синим, поле .fin — рамка 1.5, скругление 12; в фокусе рамка тёмная. */
@Composable
private fun Field(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.brain)
        Input(value, placeholder, 14.sp, minHeight = 0.dp, onChange = onChange)
    }
}

/** .fieldarea: многострочное поле промпта. */
@Composable
private fun Area(value: String, placeholder: String, onChange: (String) -> Unit) = Input(value, placeholder, 13.5.sp, minHeight = 60.dp, onChange = onChange)

@Composable
private fun Input(value: String, placeholder: String, size: TextUnit, minHeight: Dp, onChange: (String) -> Unit) {
    var focus by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(12.dp)).background(Color.White)
        .border(1.5.dp, if (focus) Ink.ink else Ink.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(placeholder, color = Ink.faint, fontSize = size)
        BasicTextField(value, onChange, textStyle = BaseText.copy(fontSize = size), cursorBrush = SolidColor(Ink.ink),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focus = it.isFocused })
    }
}

@Composable
private fun Dictate(onText: (String) -> Unit) {
    // Та же кнопка Писаря, что в листе: зажать и говорить, вверх — без рук; текст дописывается в конец.
    var msg by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    Column(Modifier.padding(start = 16.dp, end = 4.dp)) {
        VoiceRow(
            lock = false,   // в поле промпта замочек не нужен: удержал — сказал — отпустил
            voice = App.instance.voice,
            field = { Text(msg ?: tr(R.string.mode_dictate_hint), fontSize = 13.sp, color = Ink.soft) },
            trailing = null,
            onText = { msg = null; onText(it) },
            onMessage = { msg = it },
            onNeedMic = { (ctx as? Activity)?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) },
        )
    }
}
