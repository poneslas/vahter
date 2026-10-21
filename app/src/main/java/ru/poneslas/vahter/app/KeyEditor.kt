package ru.poneslas.vahter.app

import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Spacer
import ru.poneslas.vahter.ui.PrimaryButton
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import ru.poneslas.vahter.R
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import ru.poneslas.vahter.tr
import androidx.compose.ui.graphics.Color
import ru.poneslas.vahter.App
import androidx.compose.foundation.shape.CircleShape
import ru.poneslas.vahter.ui.H2
import ru.poneslas.vahter.ui.BaseText
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import ru.poneslas.vahter.ui.Ink
import androidx.compose.foundation.layout.Row
import ru.poneslas.vahter.ui.Ic
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.launch
import ru.poneslas.vahter.core.BrainDesc
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import ru.poneslas.vahter.ui.IconBtn
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
            H2(tr(R.string.key_title, brain.title))
            Text(tr(R.string.key_stored), color = Ink.soft, modifier = Modifier.padding(bottom = 12.dp))
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.5.dp, if (bad) Ink.danger else Ink.ink, RoundedCornerShape(12.dp))
            .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                if (ctl.key.isEmpty()) Text(if (has) "sk-••••••••••••••••" else tr(R.string.key_ph), color = if (has) Ink.ink else Ink.faint)
                BasicTextField(ctl.key, { ctl.key = it.trim() }, singleLine = true, textStyle = BaseText, cursorBrush = SolidColor(Ink.ink), modifier = Modifier.fillMaxWidth())
            }
            IconBtn(Ic.paste, tr(R.string.key_paste), tint = Ink.soft) { clipboard.getText()?.text?.trim()?.let { ctl.key = it } }
        }
        if (onboarding) Text(tr(R.string.key_where), fontSize = 12.5.sp, color = Ink.soft, modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 8.dp, bottom = 10.dp))
        else Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
            PrimaryButton(if (ctl.checking) tr(R.string.key_checking) else tr(R.string.key_check), enabled = ctl.canCheck) { ctl.connect(scope, onDone) }
        }
        KeyResult(ctl, reserve = onboarding)
        if (onboarding) Row(Modifier.padding(start = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Ic.info), null, Modifier.size(16.dp), tint = Ink.soft)
            Spacer(Modifier.width(8.dp))
            Text(tr(R.string.key_other), fontSize = 13.sp, color = Ink.soft)
        }
    }
}

/**
 * Итог проверки ключа (ui.js, .kres): идёт, подключено, не вышло. Подключено — две строки по смыслу: «DeepSeek подключён»,
 * под ним мелко «отвечает за 0,4 с · понимает фото» — на узком экране переносить нечего. В мастере место занято заранее ([reserve]):
 * «Подключаю…» / итог / ошибка сменяют друг друга без сдвига экрана.
 */
@Composable
private fun KeyResult(ctl: KeyCtl, reserve: Boolean) {
    val st = ctl.status
    Box(Modifier.fillMaxWidth().then(if (reserve) Modifier.heightIn(min = 66.dp) else Modifier)) {
        if (!ctl.checking && st == null) return@Box
        val (bg, fg) = when { ctl.checking -> Ink.chipBg to Ink.soft; st?.first == true -> Color(0xFFE3F1E6) to Color(0xFF1F5A2E); else -> Color(0xFFFBE7E4) to Color(0xFF8A2A1F) }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp).clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (ctl.checking) CircularProgressIndicator(Modifier.size(16.dp), color = Ink.ink, trackColor = Ink.swOff, strokeWidth = 2.dp)
            else Box(Modifier.size(22.dp).clip(CircleShape).background(if (st?.first == true) Ink.ok else Color(0xFFC9483B)), contentAlignment = Alignment.Center) {
                Text(if (st?.first == true) "✓" else "!", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                when {
                    ctl.checking -> Text(tr(R.string.key_connecting), fontSize = 14.sp, color = fg)
                    st?.first == true -> {
                        Text(tr(R.string.key_ok, ctl.brain.title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(st.second + if (ctl.brain.vision) " · " + tr(R.string.key_vision) else "", fontSize = 12.5.sp, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    else -> Text(st?.second.orEmpty(), fontSize = 14.sp, color = fg)
                }
            }
        }
    }
}
