package ru.poneslas.vahter.app

import ru.poneslas.vahter.ui.TryVoice
import androidx.compose.ui.platform.LocalContext
import ru.poneslas.vahter.ui.PrimaryButton
import androidx.compose.foundation.shape.RoundedCornerShape
import ru.poneslas.vahter.R
import androidx.compose.ui.Modifier
import ru.poneslas.vahter.tr
import ru.poneslas.vahter.App
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ru.poneslas.vahter.ui.Ink
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import ru.poneslas.vahter.ui.ScrollPage
import ru.poneslas.vahter.pisar.model.ModelDownload
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import ru.poneslas.vahter.ui.TopBar
import androidx.compose.runtime.getValue

/** Распознавание: скачать (≈240 МБ, с докачкой), прогрев, удалить. */
@Composable
fun VoicePage(onBack: () -> Unit) {
    val app = App.instance
    TopBar(tr(R.string.set_voice_title), tr(R.string.voice_subtitle), onBack = onBack)
    // отступ сверху — как у остальных страниц (раздел начинается не вплотную к шапке); у мастера свой
    ScrollPage { androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp)); VoiceBlock() }
}

/** Скачивание голоса — как шаг «Модель» у Гига Писаря для Android: одна полоса, «Попробуйте здесь» в конце. */
@Composable
fun VoiceBlock() {
    val app = App.instance
    val ctx = LocalContext.current
    val state by ModelDownload.state.collectAsState()
    val installed by app.voice.installed.collectAsState()
    val running = state as? ModelDownload.State.Running
    val error = (state as? ModelDownload.State.Failed)?.reason
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when {
            installed -> {
                Text(tr(R.string.voice_ready_h), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
                Text(tr(R.string.voice_ready_p), fontSize = 15.sp, color = Ink.soft)
                TryVoice()
            }
            running != null -> {
                Text(tr(R.string.voice_about), fontSize = 15.sp, color = Ink.soft)
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Ink.line)) {
                    Box(Modifier.fillMaxWidth(running.percent / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Ink.ink))
                }
                Text(tr(R.string.app_voice_loading, running.percent), fontSize = 14.sp, color = Ink.ink)
            }
            else -> {
                Text(tr(R.string.voice_about), fontSize = 15.sp, color = Ink.soft)
                PrimaryButton(if (error == null) tr(R.string.voice_download) else tr(R.string.voice_retry)) { ModelDownload.start(ctx) }
                if (error != null) Text(tr(R.string.voice_error, error), fontSize = 14.sp, color = Ink.danger)
            }
        }
    }
}
