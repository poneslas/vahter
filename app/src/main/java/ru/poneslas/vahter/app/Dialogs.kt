package ru.poneslas.vahter.app

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import ru.poneslas.vahter.ui.PrimaryButton
import androidx.compose.foundation.shape.RoundedCornerShape
import ru.poneslas.vahter.R
import androidx.compose.ui.Modifier
import ru.poneslas.vahter.tr
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import ru.poneslas.vahter.ui.Ink
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import ru.poneslas.vahter.ui.TextButton

/** Наши окна поверх экрана: лист-объяснение (app.css, .osheet) и выбор из списка (.dlg.rdlg). */

/** Наше окно-лист (app.css, .osheet): ручка, значок, заголовок, текст; справа действие, слева от него «Не сейчас». */
@Composable
fun PermSheetUi(a: MainActivity, d: MainActivity.PermSheet, close: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x730A0E18)).clickable(onClick = close), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Ink.paper).clickable(enabled = false) {}
            .navigationBarsPadding().padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 22.dp)) {
            Box(Modifier.fillMaxWidth().padding(bottom = 14.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Ink.grab)) }
            Box(Modifier.size(44.dp).clip(CircleShape).background(Ink.localT), contentAlignment = Alignment.Center) {
                Icon(painterResource(d.icon), null, Modifier.size(22.dp), tint = Ink.localD)
            }
            Text(d.title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            Text(d.text, fontSize = 14.5.sp, color = Ink.soft, modifier = Modifier.padding(bottom = 18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(d.later ?: tr(R.string.not_now)) { val f = d.onLater; if (f != null) { a.hideSheet(); f() } else close() }
                PrimaryButton(d.go) { a.hideSheet(); d.onGo() }
            }
        }
    }
}

/** dialog() с radio: .scrim прижат к низу, .dlg.rdlg — скругление 30, пункты .ropt, внизу «Отмена». */
@Composable
fun Choice(d: MainActivity.ChoiceDialog, close: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x730A0E18)).clickable(interactionSource = null, indication = null) { close() }
        .navigationBarsPadding().padding(14.dp), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Ink.dlg).clickable(interactionSource = null, indication = null) {}
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 14.dp)) {
            Text(d.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF14171F), modifier = Modifier.padding(bottom = 8.dp))
            d.options.forEach { (v, label) ->
                Row(Modifier.fillMaxWidth().clickable { d.onPick(v); close() }.padding(horizontal = 4.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val on = v == d.current
                    Box(Modifier.size(20.dp).clip(CircleShape).border(if (on) 6.dp else 2.dp, if (on) Color(0xFF3E6FD8) else Color(0xFF9AA0AA), CircleShape))
                    Text(label, fontSize = 14.5.sp, color = Color(0xFF14171F), modifier = Modifier.padding(start = 14.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) { TextButton(tr(R.string.ui_cancel), color = Ink.brain, onClick = close) }
        }
    }
}
