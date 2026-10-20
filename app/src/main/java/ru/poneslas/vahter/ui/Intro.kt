package ru.poneslas.vahter.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.poneslas.vahter.R
import kotlin.math.sin

/**
 * Сцены мастера первого запуска (сценарий — docs/design/prototype/intro.css, те же фазы). Каждый экран мастера сначала
 * объясняет сценой, а под ней сразу настраивает (app/Pages.kt, Onboarding). Только встроенная анимация Compose: ни библиотек,
 * ни файлов анимации — 0 КБ, 60 к/с, тексты из ресурсов. Каждая сцена — петля: [t] от 0 до 1, фазы — доли петли.
 */
enum class WizScene { WELCOME, QUEUE, CHAT, KEYS, BRAIN }

@Composable
fun WizArt(k: WizScene) = Box(Modifier.widthIn(max = 340.dp).fillMaxWidth().height(if (k == WizScene.BRAIN) 290.dp else 300.dp)) {
    when (k) { WizScene.WELCOME -> Logo(); WizScene.QUEUE -> SceneQueue(); WizScene.CHAT -> SceneChat(); WizScene.KEYS -> SceneKeys(); WizScene.BRAIN -> SceneBrain() }
}

// ── время ──────────────────────────────────────────────────────────────

/** 0…1 между [a] и [b], с мягким разгоном и торможением. */
private fun ramp(t: Float, a: Float, b: Float) = FastOutSlowInEasing.transform(((t - a) / (b - a)).coerceIn(0f, 1f))
/** Видно между [inA..inB] (появление) и [outA..outB] (исчезновение). */
private fun window(t: Float, inA: Float, inB: Float, outA: Float = 2f, outB: Float = 2f) = ramp(t, inA, inB) * (1f - ramp(t, outA, outB))

@Composable
private fun loop(ms: Int = 6000): Float {
    val tr = rememberInfiniteTransition(label = "intro")
    val t by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart), label = "t")
    return t
}

// ── общие кусочки: мини-лист, реплики, волна ───────────────────────────

@Composable
private fun MiniHeader(title: String, sub: String) = Column {
    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(26.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(Ink.grab))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Ink.server))
        Spacer(Modifier.width(6.dp))
        Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
        Text(" · $sub", fontSize = 11.5.sp, color = Ink.soft)
    }
}

@Composable
private fun MiniSheet(modifier: Modifier, round: Boolean = false, content: @Composable () -> Unit) {
    val shape = if (round) RoundedCornerShape(22.dp) else RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Box(modifier.graphicsLayer { shadowElevation = 18f; this.shape = shape; clip = true }.background(Ink.paper).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)) { content() }
}

@Composable
private fun Me(text: String, alpha: Float, modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha; translationY = (1 - alpha) * 16f }, contentAlignment = Alignment.CenterEnd) {
    Text(text, fontSize = 12.sp, color = Ink.ink, modifier = Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(14.dp, 14.dp, 3.dp, 14.dp)).background(Ink.me).padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
private fun Ai(text: String, alpha: Float, modifier: Modifier = Modifier) = Row(modifier.padding(top = 6.dp).graphicsLayer { this.alpha = alpha; translationY = (1 - alpha) * 16f }) {
    Box(Modifier.padding(top = 5.dp, end = 6.dp).size(6.dp).clip(CircleShape).background(Ink.server))
    Text(text, fontSize = 12.sp, lineHeight = 1.35.em, color = Ink.ink)
}

@Composable
private fun Wave(alpha: Float, color: Color = Ink.local) {
    if (alpha <= 0f) return
    val w = loop(900)
    Row(Modifier.fillMaxWidth().height(22.dp).graphicsLayer { this.alpha = alpha }, horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        listOf(0f, .11f, .28f, .05f, .33f, .17f, .22f).forEach { d ->
            val h = 5f + 15f * (0.5f + 0.5f * sin((w + d) * 2 * Math.PI.toFloat()))
            Box(Modifier.size(3.dp, h.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@Composable
private fun ActRow(icon: String, title: String, sub: String, alpha: Float, check: Float) = Row(
    Modifier.fillMaxWidth().padding(top = 6.dp).graphicsLayer { this.alpha = alpha; translationX = (1 - alpha) * -28f }
        .clip(RoundedCornerShape(11.dp)).background(Ink.surf).border(1.dp, Ink.line, RoundedCornerShape(11.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(Ink.localT), contentAlignment = Alignment.Center) { Text(icon, fontSize = 12.sp) }
        // галочка «пружинит»: 0 → 1.35 → 1
        val s = if (check < .5f) check * 2 * 1.35f else 1.35f - (check - .5f) * 2 * .35f
        Box(Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(12.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape).background(Ink.ok)
            .border(1.5.dp, Ink.surf, CircleShape), contentAlignment = Alignment.Center) { Text("✓", fontSize = 7.sp, lineHeight = 7.sp, color = Color.White, fontWeight = FontWeight.Bold) }
    }
    Column(Modifier.padding(start = 8.dp)) {
        Text(title, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink, maxLines = 1)
        Text(sub, fontSize = 10.sp, color = Ink.soft)
    }
}

// ── 0. девиз: логотип, столбики которого — волна голоса ───────────────

@Composable
private fun BoxScope.Logo() {
    val t = loop(2400)
    val w = loop(1200)
    Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
        listOf(0f to Ink.local, .5f to Ink.server).forEach { (d, c) ->
            val p = (t + d) % 1f
            Box(Modifier.size(132.dp).graphicsLayer { scaleX = 1 + .6f * p; scaleY = 1 + .6f * p; alpha = .6f * (1 - p) }.border(2.dp, c, RoundedCornerShape(40.dp)))
        }
        Box(Modifier.size(132.dp).graphicsLayer { shadowElevation = 30f; shape = RoundedCornerShape(40.dp); clip = true }.drawBehind {
            rotate(110f) { drawRect(Brush.sweepGradient(listOf(Ink.local, Color(0xFFF5C35A), Ink.server, Color(0xFF6F86FF), Ink.local), center), Offset(-size.width, -size.height), Size(size.width * 3, size.height * 3)) }
        }, contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().padding(24.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFF10182B)))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(0f, .15f, .3f, .1f).forEachIndexed { i, d ->
                    val h = 14f + 34f * (0.5f + 0.5f * sin((w + d) * 2 * Math.PI.toFloat()))
                    Box(Modifier.size(7.dp, h.dp).clip(RoundedCornerShape(4.dp)).background(if (i < 2) Color(0xFFF5C35A) else Color(0xFF7C93FF)))
                }
            }
        }
    }
    // табличка поста: лампа мигает — вахтёр на месте
    val lamp = loop(1600)
    val lp = .5f + .5f * sin(lamp * 2 * Math.PI.toFloat())
    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).graphicsLayer { shadowElevation = 8f; shape = RoundedCornerShape(10.dp); clip = true }
        .background(Ink.surf).border(1.dp, Ink.line, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).drawBehind { drawCircle(Color(0xFFF5C35A).copy(alpha = .7f * (1 - lp)), radius = size.minDimension / 2 + 7.dp.toPx() * lp) }
            .clip(CircleShape).background(Color(0xFFF5C35A).copy(alpha = .75f + .25f * (1 - lp))))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.intro_post), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .02.em, color = Ink.ink)
    }
}

// ── 1. своих — без очереди: гонка. Обычное приложение — талончики №1–4; Вахтёр — зажал, и вопрос уже задан ──

/** Пунктирная рамка — талончик очереди. */
private fun Modifier.dashed(color: Color, r: Dp) = drawBehind {
    drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r.toPx()),
        style = Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
}

/** Секундомер «⏱ 0:NN»: от 0 до [n] секунд к доле петли [until]. */
@Composable
private fun Clock(t: Float, n: Int, until: Float, color: Color) {
    val s = (n * (t / until).coerceIn(0f, 1f)).toInt()
    Text("⏱ 0:%02d".format(s), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
}

/** Эквалайзер диктовки в цветах Вахтёра: янтарь → синий. [calm] — спокойная бегущая волна (идёт распознавание). */
@Composable
fun DictEq(alpha: Float, bars: Int = 9, maxH: Float = 16f, w: Dp = 3.dp, calm: Boolean = false) {
    if (alpha <= 0f) return
    val ph = loop(if (calm) 1600 else 380 * 2)
    Row(Modifier.height(maxH.dp).graphicsLayer { this.alpha = alpha }, horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until bars) {
            val k = i / (bars - 1f)
            val h = 4f + (maxH - 4f) * (if (calm) .18f + .32f * (0.5f + 0.5f * sin((ph - i * .09f) * 2 * Math.PI.toFloat())) else 0.5f + 0.5f * sin((ph + i * .37f) * 2 * Math.PI.toFloat()))
            Box(Modifier.size(w, h.dp).clip(RoundedCornerShape(2.dp)).background(lerp(Ink.local, Ink.server, k)))
        }
    }
}

@Composable
private fun BoxScope.SceneQueue() {
    val t = loop(5000)
    val out = 1f - ramp(t, .97f, 1f)
    val steps = stringArrayResource(R.array.intro_steps)
    Column(Modifier.fillMaxSize().graphicsLayer { alpha = out }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Обычное ИИ-приложение: шаги-талончики загораются по одному, секундомер бежит до 0:14.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFFE2E5EB)).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) { Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(2) { Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFA9B0BE))) } } }
                }
                Spacer(Modifier.width(9.dp))
                Text(stringResource(R.string.intro_usual), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5B6478))
                Spacer(Modifier.weight(1f))
                Clock(t, 14, .70f, Color(0xFF6B7487))
            }
            Spacer(Modifier.height(9.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(5.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                steps.forEachIndexed { i, st ->
                    val on = .3f + .7f * ramp(t, .06f + i * .14f, .09f + i * .14f)
                    Row(Modifier.graphicsLayer { alpha = on }.clip(RoundedCornerShape(9.dp)).background(Color.White).dashed(Ink.line, 9.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.intro_ticket, i + 1), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8A93A5))
                        Spacer(Modifier.width(5.dp))
                        Text(st, fontSize = 12.sp, color = Ink.ink)
                    }
                    Text("→", fontSize = 13.sp, color = Ink.soft, modifier = Modifier.graphicsLayer { alpha = .2f + .8f * ramp(t, .06f + i * .14f, .09f + i * .14f) })
                }
                Text(stringResource(R.string.intro_question), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink,
                    modifier = Modifier.graphicsLayer { alpha = .3f + .7f * ramp(t, .64f, .67f) }.clip(RoundedCornerShape(9.dp)).background(Color.White)
                        .border(1.dp, Ink.line, RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        // Вахтёр: зажал — вопрос надиктован — ответ; секундомер встаёт на 0:02.
        Column(Modifier.fillMaxWidth().weight(1f).graphicsLayer { shadowElevation = 14f; shape = RoundedCornerShape(18.dp); clip = true }
            .background(Ink.localT).border(2.dp, Ink.local, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val press = window(t, 0f, .02f, .05f, .09f)
                Box(Modifier.size(28.dp).graphicsLayer { scaleX = 1 - .12f * press; scaleY = 1 - .12f * press }.clip(CircleShape).background(Ink.ink), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(Ic.power), null, Modifier.size(15.dp), tint = Ink.local)
                }
                Spacer(Modifier.width(9.dp))
                Text(stringResource(R.string.intro_ours), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink.ink)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.intro_free), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Ink.localD,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).border(1.dp, Color(0xFFEBCB86), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.weight(1f))
                Clock(t, 2, .38f, Ink.localD)
                Spacer(Modifier.width(6.dp))
                val ok = ramp(t, .38f, .42f)
                val s = if (ok < .5f) ok * 2 * 1.3f else 1.3f - (ok - .5f) * 2 * .3f
                Box(Modifier.size(22.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape).background(Ink.ok), contentAlignment = Alignment.Center) {
                    Text("✓", fontSize = 13.sp, color = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.graphicsLayer { alpha = .3f + .7f * ramp(t, .06f, .09f) }.clip(RoundedCornerShape(9.dp)).background(Color.White)
                    .border(1.dp, Color(0xFFEBCB86), RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(Ic.power), null, Modifier.size(12.dp), tint = Ink.ink)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.intro_hold), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
                }
                Text(" → ", fontSize = 13.sp, color = Ink.soft)
                Box(Modifier.graphicsLayer { alpha = .3f + .7f * ramp(t, .08f, .10f) }.clip(RoundedCornerShape(9.dp)).background(Color.White)
                    .border(1.dp, Color(0xFFEBCB86), RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                    DictEq(window(t, .09f, .11f, .29f, .31f))
                    val tx = ramp(t, .29f, .31f)
                    if (tx > 0f) Text(stringResource(R.string.intro_s1_q), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink, modifier = Modifier.graphicsLayer { alpha = tx })
                }
            }
            Ai(stringResource(R.string.intro_s1_a), ramp(t, .33f, .37f), Modifier.padding(top = 8.dp))
        }
    }
}

// ── 2. своих знает в лицо: одна ситуация (ресторан, фото меню). Слева чат — просьба печатается строка за строкой, счётчик
//    слов и секунд растёт; справа свой режим — щёлк камерой, ответ по правилам за 3 с. Обе колонки стартуют вместе ──

@Composable
private fun SceneChat() {
    val t = loop(8000)
    val out = 1f - ramp(t, .97f, 1f)
    Row(Modifier.fillMaxSize().graphicsLayer { alpha = out }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // чат: просьба заново
        Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Color(0xFFE2E5EB)).padding(11.dp)) {
            ColHead("💬", stringResource(R.string.wiz_chat), stringResource(R.string.wiz_chat_new), Color(0xFF5B6478))
            val ask = stringResource(R.string.wiz_chat_ask)
            val p = ramp(t, .03f, .78f)
            // по строкам, как печатают: 9 ступенек
            val shown = (ask.length * (kotlin.math.floor(p * 9f) / 9f)).toInt()
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp)).background(Color.White).padding(horizontal = 9.dp, vertical = 8.dp)) {
                Text(ask.take(shown), fontSize = 11.5.sp, lineHeight = 1.38.em, color = Ink.ink)
            }
            (20 * p).toInt().let { w -> Counter(androidx.compose.ui.res.pluralStringResource(R.plurals.wiz_chat_words, w, w), (40 * p).toInt(), Color(0xFF6B7487)) }
        }
        // свой режим: «как обычно»
        Column(Modifier.weight(1f).fillMaxHeight().graphicsLayer { shadowElevation = 14f; shape = RoundedCornerShape(18.dp); clip = true }
            .background(Ink.localT).border(2.dp, Ink.local, RoundedCornerShape(18.dp)).padding(11.dp)) {
            ColHead("🍽", stringResource(R.string.intro_tab_gastro), stringResource(R.string.intro_my_mode), Ink.ink)
            val th = ramp(t, .04f, .08f)
            Box(Modifier.fillMaxWidth().height(58.dp)) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = th; scaleX = .8f + .2f * th; scaleY = .8f + .2f * th }, contentAlignment = Alignment.CenterEnd) { MenuPhoto() }
                val fl = window(t, .03f, .04f, .04f, .08f)
                if (fl > 0f) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .9f * fl)))
            }
            // подпись — что ушло: фото меню, а не документ
            Text(stringResource(R.string.wiz_menu_sent), fontSize = 10.5.sp, color = Ink.localD, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp).graphicsLayer { alpha = th })
            Ai(stringResource(R.string.intro_s2_answer), ramp(t, .10f, .14f), Modifier.padding(top = 3.dp))
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                stringArrayResource(R.array.wiz_chat_checks).forEachIndexed { i, c ->
                    val on = ramp(t, .15f + i * .03f, .18f + i * .03f)
                    Text(c, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.localD, modifier = Modifier.graphicsLayer { alpha = on; scaleX = .6f + .4f * on; scaleY = .6f + .4f * on }
                        .clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 7.dp, vertical = 2.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Counter(stringResource(R.string.wiz_one_press), (3 * ramp(t, .03f, .14f)).toInt(), Ink.localD, power = true)
        }
    }
}

/** Шапка колонки: значок, название, под ним — пояснение (колонка узкая, в одну строку не помещается). */
@Composable
private fun ColHead(icon: String, title: String, sub: String, color: Color) = Row(Modifier.fillMaxWidth().padding(bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(icon, fontSize = 17.sp)
    Spacer(Modifier.width(7.dp))
    Column {
        Text(title, fontSize = 14.sp, lineHeight = 1.1.em, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(sub, fontSize = 10.5.sp, lineHeight = 1.1.em, color = Ink.soft, maxLines = 1)
    }
}

/** Нижняя строка колонки: «✎ 20 слов» / «⏻ 1 нажатие» слева, «0:40» справа. ⏻ — значком: в системном шрифте его нет. */
@Composable
private fun Counter(left: String, sec: Int, color: Color, power: Boolean = false) = Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    if (power) { androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(Ic.power), null, Modifier.size(12.dp), tint = color); Spacer(Modifier.width(4.dp)) }
    Text(left, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    Spacer(Modifier.weight(1f))
    Text("0:%02d".format(sec), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
}

// ── 3. знает, кто зачем пришёл: режимы — кнопки; по очереди переворачиваются, на обороте — уже написанная просьба ──

@Composable
private fun SceneKeys() {
    val t = loop(10000)
    val k = stringArrayResource(R.array.wiz_keys)          // «иконка|название|подпись|оборот»
    // [начало переворота, конец] для каждой кнопки — доли петли (intro.css, fp0…fp3)
    val win = listOf(.04f to .20f, .26f to .42f, .48f to .64f, .70f to .92f)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in 0..1) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (col in 0..1) {
                val i = row * 2 + col
                val (icon, title, tag, back) = k[i].split('|')
                val (a, b) = win[i]
                val turn = window(t, a, a + .04f, b, b + .04f)        // 0 — лицо, 1 — оборот
                val deg = 180f * turn
                Box(Modifier.weight(1f).fillMaxHeight().graphicsLayer { rotationY = deg; cameraDistance = 12f * density }) {
                    if (deg <= 90f) {
                        val own = i == 3
                        Column(Modifier.fillMaxSize().graphicsLayer { shadowElevation = 8f; shape = RoundedCornerShape(18.dp); clip = true }
                            .background(if (own) Ink.localT else Ink.surf).border(if (own) 2.dp else 1.5.dp, if (own) Ink.local else Ink.line, RoundedCornerShape(18.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween) {
                            Text(icon, fontSize = 22.sp)
                            Column {
                                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink.ink, maxLines = 1)
                                Text(tag, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (i == 0) Ink.soft else Ink.localD, maxLines = 1)
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.clip(RoundedCornerShape(18.dp)).background(Color(0xFFFFFBEF))
                            .dashed(Color(0xFFE3C77E), 18.dp).padding(12.dp)) {
                            Text(stringResource(R.string.wiz_back).uppercase(), fontSize = 9.5.sp, letterSpacing = .06.em, color = Ink.localD, modifier = Modifier.padding(bottom = 4.dp))
                            Text(back, fontSize = 12.sp, lineHeight = 1.35.em, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Ink.ink)
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.wiz_plus), fontSize = 12.5.sp, color = Ink.soft, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Снимок меню: карточка меню на тёмном столе, чуть повёрнута — видно, что это фото (intro.css, .sc6 .thumb). */
@Composable
private fun MenuPhoto() = Box(Modifier.size(76.dp, 56.dp).graphicsLayer { shadowElevation = 8f; shape = RoundedCornerShape(10.dp); clip = true }
    .background(Brush.radialGradient(listOf(Color(0xFF7A5236), Color(0xFF3E2718)), center = Offset(70f, 40f), radius = 220f))) {
    Box(Modifier.offset(14.dp, 6.dp).size(44.dp, 52.dp).graphicsLayer { rotationZ = -8f; shadowElevation = 6f; shape = RoundedCornerShape(3.dp) }
        .background(Color(0xFFF4EBD6)).padding(horizontal = 7.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("МЕНЮ", fontSize = 6.5.sp, lineHeight = 7.sp, letterSpacing = .12.em, fontWeight = FontWeight.Bold, color = Color(0xFF5B4630))
            Spacer(Modifier.height(4.dp))
            repeat(5) { i -> Box(Modifier.padding(top = 3.dp).fillMaxWidth(if (i % 2 == 0) 1f else .75f).height(1.dp).background(Color(0xFFB08A57))) }
        }
    }
}

@Composable
private fun Thumb(menu: Boolean) = Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
    Canvas(Modifier.size(76.dp, 56.dp).clip(RoundedCornerShape(12.dp))) {
        if (menu) {
            drawRect(Brush.linearGradient(listOf(Color(0xFFF7F1E3), Color(0xFFE6D8B8))))
            for (i in 0..4) drawLine(Color(0x8C8E6E3E), Offset(10.dp.toPx(), (10 + i * 9).dp.toPx()), Offset(size.width - 10.dp.toPx(), (10 + i * 9).dp.toPx()), 2.dp.toPx())
        } else {
            drawRect(Color(0xFFE8DCC4))
            drawCircle(Color(0xFFA8743F), size.minDimension * .42f, Offset(size.width / 2, size.height * .58f))
            drawCircle(Color(0xFF6B3E20), size.minDimension * .32f, Offset(size.width / 2, size.height * .58f))
        }
    }
}

// ── мастер, «Подключите мозг»: простое — сам на телефоне (даже без сети); сложное — пакет вверх, к «Мозгу Вахтёра»
//    в облаке, ответ обратно. Мозг абстрактный: под именем тихо сменяется DeepSeek · OpenAI · Hermes ──

@Composable
private fun BoxScope.SceneBrain() {
    val t = loop(7000)
    val who = loop(6000)
    // наверху — «Мозг Вахтёра»
    val glow = window(t, .58f, .61f, .66f, .72f)
    Row(Modifier.align(Alignment.TopCenter).width(232.dp).height(66.dp)
        .drawBehind { drawRoundRect(Ink.server.copy(alpha = .22f * glow), Offset(-6.dp.toPx(), -6.dp.toPx()), Size(size.width + 12.dp.toPx(), size.height + 12.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(22.dp.toPx())) }
        .clip(RoundedCornerShape(16.dp)).background(Ink.serverT).border(1.5.dp, Ink.server, RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Color.White), contentAlignment = Alignment.Center) { Text("🧠", fontSize = 17.sp) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.wiz_brain_name), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink.ink)
            val names = stringArrayResource(R.array.wiz_brain_who)
            val i = (who * names.size).toInt().coerceIn(0, names.size - 1)
            val f = who * names.size - i                                // 0…1 внутри своей трети
            val a = window(f, 0f, .18f, .82f, 1f)
            Text(names[i], fontSize = 10.5.sp, color = Ink.server, modifier = Modifier.graphicsLayer { alpha = a; translationY = 16f * (1 - a) * (if (f < .5f) 1 else -1) })
        }
        Text("☁", fontSize = 22.sp, color = Color(0xFFB7C2F4))
    }
    // провод и пакет: вверх синий (вопрос), вниз янтарный (ответ)
    Canvas(Modifier.align(Alignment.TopCenter).padding(top = 70.dp).size(10.dp, 44.dp)) {
        var y = 0f
        while (y < size.height) { drawRect(Ink.server.copy(alpha = .55f), Offset(size.width / 2 - 1.dp.toPx(), y), Size(2.dp.toPx(), 4.dp.toPx())); y += 8.dp.toPx() }
        val up = ramp(t, .51f, .59f); val down = ramp(t, .65f, .72f)
        val y0 = size.height - 5.dp.toPx(); val y1 = 0f
        when {
            t in .51f..<.61f -> drawCircle(Ink.server, 5.dp.toPx(), Offset(size.width / 2, y0 + (y1 - y0) * up))
            t in .65f..<.73f -> drawCircle(Ink.local, 5.dp.toPx(), Offset(size.width / 2, y1 + (y0 - y1) * down))
        }
    }
    // лист: сначала сам (будильник, таймер, «нет сети — всё равно сам»), потом сложный вопрос — наверх и ответ
    MiniSheet(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(top = 112.dp).height(178.dp), round = true) {
        Box(Modifier.fillMaxSize()) {
            MiniHeader(stringResource(R.string.intro_assistant), stringResource(R.string.intro_online))
            val pa = 1f - ramp(t, .40f, .44f)
            if (pa > 0f) Column(Modifier.padding(top = 26.dp).graphicsLayer { alpha = pa }) {
                ActRow("⏰", stringResource(R.string.intro_s4_r1), stringResource(R.string.wiz_brain_r1s), ramp(t, .03f, .08f), ramp(t, .08f, .14f))
                ActRow("⏱", stringResource(R.string.intro_s4_r2), stringResource(R.string.intro_s4_r2s), ramp(t, .13f, .18f), ramp(t, .18f, .24f))
                val off = ramp(t, .25f, .30f)
                Text(stringResource(R.string.wiz_brain_off), fontSize = 11.sp, color = Ink.soft, modifier = Modifier.padding(top = 6.dp)
                    .graphicsLayer { alpha = off; scaleX = .9f + .1f * off; scaleY = .9f + .1f * off }.clip(RoundedCornerShape(50)).background(Ink.chipBg).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            val pb = window(t, .43f, .46f, .96f, 1f)
            if (pb > 0f) Column(Modifier.padding(top = 26.dp).graphicsLayer { alpha = pb }) {
                Me(stringResource(R.string.wiz_brain_q), ramp(t, .44f, .48f))
                Box(Modifier.padding(top = 8.dp)) {
                    val call = window(t, .49f, .51f, .70f, .73f) * (if (t in .54f..<.66f) .7f + .3f * sin(t * 160f) else 1f)
                    Text(stringResource(R.string.wiz_brain_call), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Ink.server, modifier = Modifier.graphicsLayer { alpha = call })
                    Ai(stringResource(R.string.wiz_brain_a), ramp(t, .71f, .75f), Modifier.padding(top = 0.dp))
                }
            }
        }
    }
}

private fun lerp(a: Color, b: Color, f: Float) = Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, a.alpha + (b.alpha - a.alpha) * f)

// ── первый запуск, «Голос на телефоне»: речь → GigaAM на телефоне → текст; облако зачёркнуто (intro.css, .scv) ──

@Composable
fun VoiceArt() {
    val t = loop(5000)
    Column(Modifier.height(280.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
            Text("☁", fontSize = 40.sp, lineHeight = 40.sp, color = Color(0xFFB9C0CC))
            val x = ramp(t, .46f, .54f) * (1 - ramp(t, .96f, 1f))
            Canvas(Modifier.size(52.dp, 44.dp)) {
                if (x > 0f) rotate(-20f) {
                    val y = size.height / 2
                    drawLine(Color(0xFFC9483B), Offset(0f, y), Offset(size.width * x, y), 3.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
        Canvas(Modifier.size(2.dp, 30.dp)) {
            var y = 0f
            while (y < size.height) { drawRect(Color(0xB3B9C0CC), Offset(0f, y), Size(size.width, 4.dp.toPx())); y += 8.dp.toPx() }
        }
        Column(Modifier.width(240.dp).weight(1f).graphicsLayer { shadowElevation = 16f; shape = RoundedCornerShape(24.dp); clip = true }
            .background(Ink.surf).border(3.dp, Ink.ink, RoundedCornerShape(24.dp)).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.localT).padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                val glow = window(t, .14f, .24f, .40f, .50f)
                Box(Modifier.size(30.dp).drawBehind { drawRoundRect(Ink.local.copy(alpha = .35f * glow), Offset(-6.dp.toPx(), -6.dp.toPx()), Size(size.width + 12.dp.toPx(), size.height + 12.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())) }
                    .clip(RoundedCornerShape(8.dp)).background(Ink.ink).padding(8.dp)) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)).background(Ink.local))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.onb_voice_model), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Ink.ink)
                    Text(stringResource(R.string.onb_voice_model_sub), fontSize = 11.sp, color = Ink.localD)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val eq = ramp(t, .06f, .10f) * (1 - ramp(t, .38f, .44f))
                DictEq(eq, bars = 9, maxH = 38f, w = 5.dp)
                val q = stringResource(R.string.intro_s1_q)
                val n = (q.length * ramp(t, .42f, .62f)).toInt()
                if (n > 0) Text(q.take(n), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink, maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = 1 - ramp(t, .96f, 1f) })
            }
        }
        val l = ramp(t, .52f, .58f) * (1 - ramp(t, .96f, 1f))
        Text(stringResource(R.string.onb_voice_lock), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink.localD, maxLines = 1,
            modifier = Modifier.padding(top = 10.dp).graphicsLayer { alpha = l; translationY = 12f * (1 - l) }.clip(RoundedCornerShape(50)).background(Ink.localT).padding(horizontal = 12.dp, vertical = 5.dp))
    }
}
