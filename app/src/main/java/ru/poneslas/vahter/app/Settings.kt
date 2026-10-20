package ru.poneslas.vahter.app

import ru.poneslas.vahter.R
import ru.poneslas.vahter.tr
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
import ru.poneslas.vahter.ui.Ic
import ru.poneslas.vahter.ui.BaseText
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
import ru.poneslas.vahter.App
import ru.poneslas.vahter.core.BrainDesc
import ru.poneslas.vahter.ui.Ink

/** Строительные блоки экранов — по правилам docs/design/prototype/app.css (имена классов в комментариях). */

/** .topbar: [назад] заголовок 19 / подзаголовок 12 с точкой связи, справа — действия. */
@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)?, dot: Color? = null, backIcon: Int = Ic.back, titleIcon: Int? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 52.dp).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconBtn(backIcon, tr(R.string.kit_nazad), onClick = onBack)
        Column(Modifier.weight(1f).padding(start = if (onBack != null) 6.dp else 12.dp)) {
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

/** Состояние ключа: поле, проверка, итог. В мастере «Подключить» — главная кнопка внизу, поэтому состояние снаружи. */
class KeyCtl(val brain: BrainDesc) {
    var key by mutableStateOf("")
    var checking by mutableStateOf(false)
    /** Итог одной строкой: true — подключено (text = «отвечает за 0,4 с»), false — что не так. */
    var status by mutableStateOf<Pair<Boolean, String>?>(null)
    val canCheck get() = !checking && key.length > 10
    fun connect(scope: kotlinx.coroutines.CoroutineScope, onDone: (() -> Unit)? = null) {
        if (!canCheck) return
        val app = App.instance
        val k = key
        checking = true; status = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { app.apiBrain(brain).check(k) }
            checking = false
            status = r.ok to r.text
            if (r.ok) { app.setKey(brain.id, k); key = ""; onDone?.invoke() }
        }
    }
}

@Composable
fun rememberKeyCtl(brain: BrainDesc) = remember(brain.id) { KeyCtl(brain) }

/**
 * Ключ мозга (ui.js, keyBody): поле со значком «Вставить», итог проверки — одной строкой («Подключаю…» → «✓ DeepSeek подключён ·
 * отвечает за 0,4 с» или что не так). Проверки (формат, связь, ключ, модель) идут внутри. На странице мозга — своя кнопка «Проверить»;
 * в мастере ([onboarding]) — пояснение «Где взять ключ?», «Другие модели — в настройках», кнопка — внизу мастера.
 */
@Composable
fun KeyEditor(brain: BrainDesc, onboarding: Boolean = false, ctl: KeyCtl = rememberKeyCtl(brain), onDone: (() -> Unit)? = null) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val keyV by app.keyVersion.collectAsState()
    val has = remember(keyV) { app.hasKey(brain.id) }
    val bad = ctl.status?.first == false

    Column(Modifier.fillMaxWidth()) {
        if (!onboarding) {
            H2(tr(R.string.kit_klyuch_1_s, brain.title))
            Text(tr(R.string.kit_khranitsya_v_zashchishchennom_khranilish), color = Ink.soft, modifier = Modifier.padding(bottom = 12.dp))
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.5.dp, if (bad) Ink.danger else Ink.ink, RoundedCornerShape(12.dp))
            .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                if (ctl.key.isEmpty()) Text(if (has) "sk-••••••••••••••••" else tr(R.string.key_ph), color = if (has) Ink.ink else Ink.faint)
                BasicTextField(ctl.key, { ctl.key = it.trim() }, singleLine = true, textStyle = BaseText, cursorBrush = SolidColor(Ink.ink), modifier = Modifier.fillMaxWidth())
            }
            IconBtn(Ic.paste, tr(R.string.kit_vstavit), tint = Ink.soft) { clipboard.getText()?.text?.trim()?.let { ctl.key = it } }
        }
        if (onboarding) Text(tr(R.string.key_where), fontSize = 12.5.sp, color = Ink.soft, modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 8.dp, bottom = 10.dp))
        else Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
            PrimaryButton(if (ctl.checking) tr(R.string.kit_proveryayu) else tr(R.string.kit_proverit), enabled = ctl.canCheck) { ctl.connect(scope, onDone) }
        }
        KeyResult(ctl)
        if (onboarding) Row(Modifier.padding(start = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(Ic.info), null, Modifier.size(16.dp), tint = Ink.soft)
            Spacer(Modifier.width(8.dp))
            Text(tr(R.string.key_other), fontSize = 13.sp, color = Ink.soft)
        }
    }
}

/** Итог проверки ключа — одной строкой (ui.js, .kres): идёт, подключено, не вышло. */
@Composable
private fun KeyResult(ctl: KeyCtl) {
    val st = ctl.status
    if (!ctl.checking && st == null) return
    val (bg, fg) = when { ctl.checking -> Ink.chipBg to Ink.soft; st?.first == true -> Color(0xFFE3F1E6) to Color(0xFF1F5A2E); else -> Color(0xFFFBE7E4) to Color(0xFF8A2A1F) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (ctl.checking) androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = Ink.ink, trackColor = Ink.swOff, strokeWidth = 2.dp)
        else Box(Modifier.size(22.dp).clip(CircleShape).background(if (st?.first == true) Ink.ok else Color(0xFFC9483B)), contentAlignment = Alignment.Center) {
            Text(if (st?.first == true) "✓" else "!", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(when { ctl.checking -> tr(R.string.key_connecting); st?.first == true -> tr(R.string.key_ok, ctl.brain.title, st.second); else -> st?.second.orEmpty() },
                fontSize = 14.sp, color = fg)
            if (st?.first == true && ctl.brain.vision) Text(tr(R.string.key_vision), fontSize = 12.sp, color = Ink.soft)
        }
    }
}
