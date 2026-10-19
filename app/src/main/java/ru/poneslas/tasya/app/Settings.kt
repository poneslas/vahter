package ru.poneslas.tasya.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.poneslas.tasya.App
import ru.poneslas.tasya.core.BrainDesc
import ru.poneslas.tasya.ui.Ink

/** Общие строительные блоки экранов настроек — в духе Android-настроек (Продукт 5.11). */

@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconBtn("←", "Назад", onBack)
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Ink.soft)
        }
    }
}

@Composable
fun IconBtn(glyph: String, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = 20.sp, color = Ink.ink)
    }
}

@Composable
fun Section(title: String) =
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.server, modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 4.dp))

@Composable
fun Item(title: String, subtitle: String? = null, warn: Boolean = false, trailing: String? = null, trailingColor: Color = Ink.soft, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = Ink.ink)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = if (warn) Ink.danger else Ink.soft)
        }
        if (trailing != null) Text(trailing, fontSize = 14.sp, color = trailingColor, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
fun SwitchItem(title: String, subtitle: String?, on: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = Ink.ink)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Ink.soft)
        }
        Box(Modifier.size(width = 52.dp, height = 32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Ink.ink else Ink.line), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.padding(4.dp).size(24.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
        modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(if (enabled) Ink.ink else Ink.faint)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp))
}

@Composable
fun TextButton(label: String, onClick: () -> Unit) {
    Text(label, color = Ink.server, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
        modifier = Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp))
}

@Composable
fun Note(text: String, ok: Boolean? = null) {
    Text(text, fontSize = 14.sp, color = when (ok) { true -> Ink.ok; false -> Ink.danger; null -> Ink.soft },
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(when (ok) { true -> Color(0xFFDCEFE3); false -> Ink.dangerT; null -> Color.Transparent }).padding(if (ok == null) 0.dp else 12.dp))
}

@Composable
fun ScrollPage(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), content = content)

/**
 * Ключ мозга: вставить → «Проверить» (пробный запрос) → в Keystore. Используется в настройках и в первом запуске.
 */
@Composable
fun KeyEditor(brain: BrainDesc, onDone: (() -> Unit)? = null) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Pair<Boolean?, String>?>(null) }
    var checking by remember { mutableStateOf(false) }
    val has = remember(app.keyVersion.value) { app.hasKey(brain.id) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ключ хранится в защищённом хранилище телефона (Keystore).", fontSize = 14.sp, color = Ink.soft)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.surf).border(1.5.dp, Ink.line, RoundedCornerShape(12.dp)).padding(12.dp)) {
            if (key.isEmpty()) Text(if (has) "sk-•••••••• — ключ сохранён" else "sk-…", color = Ink.faint, fontSize = 15.sp)
            BasicTextField(key, { key = it.trim() }, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = Ink.ink), cursorBrush = SolidColor(Ink.ink), modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton("Вставить") { clipboard.getText()?.text?.trim()?.let { key = it } }
            Spacer(Modifier.width(6.dp))
            PrimaryButton(if (checking) "Проверяю…" else "Проверить", enabled = !checking && key.length > 10) {
                checking = true; status = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { app.apiBrain(brain).check(key) }
                    checking = false
                    status = r.ok to r.text
                    if (r.ok) { app.setKey(brain.id, key); key = ""; onDone?.invoke() }
                }
            }
        }
        status?.let { (ok, text) -> Note(if (ok == true) "Готово: ${brain.model} $text. Ключ сохранён." else text, ok) }
        if (has && status == null) Note("Ключ сохранён. Чтобы заменить — вставьте новый и нажмите «Проверить».")
    }
}
