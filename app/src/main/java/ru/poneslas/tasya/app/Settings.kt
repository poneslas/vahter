package ru.poneslas.tasya.app

import ru.poneslas.tasya.R
import ru.poneslas.tasya.tr
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import ru.poneslas.tasya.ui.Ic
import ru.poneslas.tasya.ui.BaseText
import androidx.compose.runtime.collectAsState
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

/** Строительные блоки экранов — по правилам docs/design/prototype/app.css (имена классов в комментариях). */

/** .topbar: [назад] заголовок 19 / подзаголовок 12 с точкой связи, справа — действия. */
@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)?, dot: Color? = null, backIcon: Int = Ic.back, titleIcon: Int? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconBtn(backIcon, tr(R.string.kit_nazad), onClick = onBack)
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (titleIcon != null) { Icon(painterResource(titleIcon), null, Modifier.size(20.dp), tint = Ink.ink); Spacer(Modifier.width(8.dp)) }
                Text(title, fontSize = 19.sp, lineHeight = 1.2.em, fontWeight = FontWeight.SemiBold, color = Ink.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (subtitle != null) Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) { Box(Modifier.size(8.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(6.dp)) }
                Text(subtitle, fontSize = 12.sp, lineHeight = 1.3.em, color = Ink.soft)
            }
        }
        actions()
    }
}

/** .iconbtn: круг 44, иконка 20. */
@Composable
fun IconBtn(icon: Int, label: String, size: Dp = 44.dp, tint: Color = Ink.ink, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = tint)
    }
}

/** .lh: заголовок раздела. */
@Composable
fun Section(title: String) =
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.serverD, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 6.dp))

/** .li: иконка 24 · заголовок 15.5/500 · пояснение 13 (до двух строк); .warn — красным с треугольником. */
@Composable
fun Item(
    title: String, subtitle: String? = null, warn: Boolean = false, trailing: String? = null, trailingColor: Color = Ink.soft,
    icon: Int? = null, end: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier, minHeight: Dp = 64.dp, onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = minHeight).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null || warn) {
            Icon(painterResource(if (warn) Ic.warn else icon!!), null, Modifier.size(24.dp).padding(2.dp), tint = if (warn) Ink.danger else Ink.soft)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.5.sp, lineHeight = 1.35.em, fontWeight = FontWeight.Medium, color = Ink.ink)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, lineHeight = 1.35.em, color = if (warn) Ink.danger else Ink.soft,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
        }
        if (trailing != null) Text(trailing, fontSize = 14.sp, color = trailingColor, modifier = Modifier.padding(start = 12.dp))
        if (end != null) { Spacer(Modifier.width(12.dp)); end() }
    }
}

/** .lirow + .swbtn: строка-ссылка и отдельный переключатель за тонкой чертой. */
@Composable
fun ItemWithSwitch(title: String, subtitle: String?, icon: Int?, on: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Item(title, subtitle, icon = icon, onClick = onClick) }
        Box(Modifier.width(1.dp).height(40.dp).background(Ink.line))
        Box(Modifier.height(40.dp).clickable(onClick = onToggle).padding(start = 16.dp, end = 22.dp), contentAlignment = Alignment.Center) { Sw(on) }
    }
}

@Composable
fun SwitchItem(title: String, subtitle: String?, on: Boolean, icon: Int? = null, onToggle: () -> Unit) =
    Item(title, subtitle, icon = icon, end = { Sw(on) }, onClick = onToggle)

/** .sw: 52×32; включён — тёмный, кружок 24 справа; выключен — серый, кружок 16 слева. */
@Composable
fun Sw(on: Boolean) {
    Box(Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Ink.ink else Ink.swOff)) {
        if (on) Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).size(24.dp).clip(CircleShape).background(Color.White))
        else Box(Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(16.dp).clip(CircleShape).background(Ink.swOffThumb))
    }
}

/** .setcard: белая карточка с рамкой, скругление 18. */
@Composable
fun SetCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(18.dp)).background(Ink.surf)
        .border(1.dp, Ink.line, RoundedCornerShape(18.dp)).padding(vertical = 4.dp), content = content)

/** .divider */
@Composable
fun Divider() = Box(Modifier.padding(horizontal = 22.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(Ink.line))

/** .fbtn: тёмная «таблетка». */
@Composable
fun PrimaryButton(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(50)).background(if (enabled) Ink.ink else Ink.swOff)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** .tbtn: синяя текстовая кнопка 13.5. */
@Composable
fun TextButton(label: String, color: Color = Ink.serverD, onClick: () -> Unit) {
    Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp))
}

/** .note: янтарная; .note.ok — зелёная; .note.bad — красная. */
@Composable
fun Note(text: String, ok: Boolean? = null, modifier: Modifier = Modifier) {
    Text(text, fontSize = 13.sp, lineHeight = 1.4.em, color = when (ok) { true -> Ink.ok; false -> Ink.danger; null -> Ink.noteText },
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(when (ok) { true -> Ink.okT; false -> Ink.dangerT; null -> Ink.localT }).padding(horizontal = 12.dp, vertical = 10.dp))
}

/** .mnote: мелкое пояснение. */
@Composable
fun MNote(text: String, modifier: Modifier = Modifier) =
    Text(text, fontSize = 12.5.sp, lineHeight = 1.45.em, color = Ink.soft, modifier = modifier.padding(top = 6.dp))

/** .h2: крупный заголовок блока. */
@Composable
fun H2(text: String) = Text(text, fontSize = 22.sp, lineHeight = 1.2.em, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))

/** .mchip: «таблетка» с рамкой; выбранная — тёмная. */
@Composable
fun MChip(label: String, on: Boolean, icon: Int? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(if (on) Ink.ink else Ink.surf).border(1.dp, if (on) Ink.ink else Ink.line, RoundedCornerShape(50))
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(painterResource(icon), null, Modifier.size(16.dp), tint = if (on) Color.White else Ink.ink); Spacer(Modifier.width(6.dp)) }
        Text(label, fontSize = 13.sp, color = if (on) Color.White else Ink.ink)
    }
}

/** .banner: карточка с иконкой, текстом, полосой и кнопками справа. */
@Composable
fun Banner(text: String, error: Boolean = false, progress: Int? = null, actions: List<Pair<String, () -> Unit>> = emptyList()) {
    Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ink.surf)
        .border(1.dp, Ink.line, RoundedCornerShape(18.dp)).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)) {
        Icon(painterResource(if (error) Ic.warn else Ic.info), null, Modifier.padding(top = 2.dp).size(20.dp), tint = if (error) Ink.danger else Ink.localD)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 13.5.sp, lineHeight = 1.45.em, color = Ink.ink, modifier = Modifier.padding(bottom = if (actions.isEmpty() && progress == null) 6.dp else 0.dp))
            if (progress != null) Progress(progress, Modifier.padding(top = 6.dp, bottom = 8.dp))
            if (actions.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions.forEach { (t, a) -> TextButton(t, onClick = a) } }
        }
    }
}

/** .prog: полоса 6, янтарная. */
@Composable
fun Progress(percent: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Ink.prog)) {
        Box(Modifier.fillMaxWidth((percent / 100f).coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Ink.local))
    }
}

@Composable
fun ScrollPage(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp), content = content)

/** .mform: страница-форма с отступами 16. */
@Composable
fun FormPage(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content)

/**
 * Ключ мозга (ui.js, keyBody): заголовок, пояснение, поле с тёмной рамкой, «Вставить» · «Проверить», итог.
 * Используется на странице мозга и в первом запуске ([onboarding]).
 */
@Composable
fun KeyEditor(brain: BrainDesc, onboarding: Boolean = false, onDone: (() -> Unit)? = null) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Pair<Boolean?, String>?>(null) }
    var checking by remember { mutableStateOf(false) }
    val keyV by app.keyVersion.collectAsState()
    val has = remember(keyV) { app.hasKey(brain.id) }

    Column(Modifier.fillMaxWidth()) {
        H2(if (onboarding) tr(R.string.kit_mozg) else tr(R.string.kit_klyuch_1_s, brain.title))
        Text(if (onboarding) tr(R.string.kit_otvechat_budet_1_s_nuzhen, brain.title)
            else tr(R.string.kit_khranitsya_v_zashchishchennom_khranilish), color = Ink.soft, modifier = Modifier.padding(bottom = 12.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.5.dp, if (status?.first == false) Ink.danger else Ink.ink, RoundedCornerShape(12.dp)).padding(12.dp)) {
            if (key.isEmpty()) Text(if (has) "sk-••••••••••••••••" else "sk-…", color = if (has) Ink.ink else Ink.faint)
            BasicTextField(key, { key = it.trim() }, singleLine = true, textStyle = BaseText, cursorBrush = SolidColor(Ink.ink), modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            TextButton(tr(R.string.kit_vstavit)) { clipboard.getText()?.text?.trim()?.let { key = it } }
            PrimaryButton(if (checking) tr(R.string.kit_proveryayu) else tr(R.string.kit_proverit), enabled = !checking && key.length > 10) {
                checking = true; status = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { app.apiBrain(brain).check(key) }
                    checking = false
                    status = r.ok to r.text
                    if (r.ok) { app.setKey(brain.id, key); key = ""; onDone?.invoke() }
                }
            }
        }
        status?.let { (ok, text) -> Note(if (ok == true) tr(R.string.kit_gotovo_1_s_2_s, brain.model, text, brain.title) else text, ok) }
        if (onboarding) Text(tr(R.string.kit_svoy_server_hermes_s_pamyatyu),
            fontSize = 12.5.sp, color = Ink.soft, modifier = Modifier.padding(top = 12.dp))
    }
}
