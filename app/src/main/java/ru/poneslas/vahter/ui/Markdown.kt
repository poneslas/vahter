package ru.poneslas.vahter.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.poneslas.vahter.App

/** Простая разметка ответа: «# заголовок», «- пункт», «1. пункт», **жирный**, `код`, блоки ``` . */
@Composable
fun Markdown(text: String) {
    val blocks = remember(text) { mdBlocks(text) }
    blocks.forEach { (kind, line) ->
        when (kind) {
            'h' -> Text(mdInline(line), fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.3.em)
            'l' -> Row { Text("•", modifier = Modifier.padding(start = 4.dp, end = 8.dp)); Text(mdInline(line)) }
            'n' -> Text(mdInline(line), modifier = Modifier.padding(start = 4.dp))
            // код — без переноса, длинные строки листаются вбок внутри блока, лента не раздвигается
            'c' -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF10182B)).horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)) { Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFE3E8F6), softWrap = false) }
            't' -> MdTable(line)
            else -> Text(mdInline(line))
        }
    }
}

/** «1. пункт», «1) пункт» */
private val NUMBERED = Regex("""^\d+[.)] """)

/** Блоки ответа: вид (h — заголовок, l — пункт, n — нумерованный, c — код, t — таблица, p — абзац) и текст. */
internal fun mdBlocks(text: String): List<Pair<Char, String>> {
    val out = mutableListOf<Pair<Char, String>>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += 'p' to para.toString().trim(); para.clear() }
    var code: StringBuilder? = null
    for (raw in text.lines()) {
        val l = raw.trimEnd()
        if (l.trimStart().startsWith("```")) { if (code == null) { flush(); code = StringBuilder() } else { out += 'c' to code.toString().trimEnd(); code = null }; continue }
        if (code != null) { code.appendLine(raw); continue }
        val t = l.trimStart()
        when {
            t.isEmpty() -> flush()
            // таблица GFM: подряд строки с «|» — один блок (раньше склеивались в абзац строкой из палочек)
            t.startsWith("|") -> { if (out.lastOrNull()?.first == 't' && para.isEmpty()) out[out.lastIndex] = 't' to out.last().second + "\n" + t else { flush(); out += 't' to t } }
            t.startsWith("#") -> { flush(); out += 'h' to t.trimStart('#').trim() }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> { flush(); out += 'l' to t.drop(2) }
            NUMBERED.containsMatchIn(t) -> { flush(); out += 'n' to t }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
        }
    }
    code?.let { out += 'c' to it.toString().trimEnd() }
    flush()
    return out
}

/** Строка-разделитель шапки таблицы: |---|:--:| */
private val TABLE_SEP = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$""")

/**
 * Таблица Markdown: шапка жирным на подложке, тонкие линии; ширина колонки — по самому длинному тексту (от 64 до 220 dp),
 * в ячейке перенос. Шире ленты — таблица листается вбок внутри себя (как в Telegram/ChatGPT), лента не раздвигается.
 */
@Composable
private fun MdTable(src: String) {
    val rows = remember(src) {
        src.lines().map { it.trim() }.filter { it.isNotEmpty() && !TABLE_SEP.matches(it) }
            .map { it.removePrefix("|").removeSuffix("|").split("|").map { c -> c.trim() } }
    }
    if (rows.isEmpty()) return
    val n = rows.maxOf { it.size }
    val widths = (0 until n).map { c -> (rows.maxOf { (it.getOrNull(c) ?: "").length } * 8.2f + 20f).coerceIn(64f, 220f).dp }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Ink.line, RoundedCornerShape(10.dp))) {
            rows.forEachIndexed { r, row ->
                Row(Modifier.then(if (r == 0) Modifier.background(Ink.chipBg) else Modifier).height(IntrinsicSize.Min)) {
                    for (c in 0 until n) {
                        if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Ink.line))
                        Text(mdInline(row.getOrNull(c) ?: ""), fontSize = 13.5.sp, lineHeight = 1.3.em, fontWeight = if (r == 0) FontWeight.SemiBold else null,
                            modifier = Modifier.width(widths[c]).padding(horizontal = 9.dp, vertical = 6.dp))
                    }
                }
                if (r < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
            }
        }
    }
}

/** Ссылка — подчёркнутое слово, как везде; без значков. */
private val LINK = TextLinkStyles(SpanStyle(color = Ink.serverD, textDecoration = TextDecoration.Underline))
/** Ссылка открывается явно: системным «Открыть» по адресу (браузер или приложение сайта). */
private fun link(url: String) = LinkAnnotation.Clickable(url, LINK) {
    runCatching { App.instance.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
// Шаблоны строчной разметки прикладываются к позиции (Regex.matchAt) — поэтому без «^».
/** [текст](адрес), [текст](<адрес>), [текст](адрес "подсказка") */
private val MD_LINK = Regex("""\[([^\]]+)]\(<?(https?://[^)\s>]+)>?(?:\s+"[^"]*")?\)""")
/** <адрес> */
private val ANGLE_LINK = Regex("""<(https?://[^>\s]+)>""")
/** голый адрес; хвостовые знаки препинания — не часть ссылки */
private val BARE_LINK = Regex("""https?://[^\s<>()\[\]]+""")
/** 🔗 (с вариантом-эмодзи U+FE0F или без) перед ссылкой */
private val LINK_ICON = Regex("""🔗️?\s*(\[|<|https?://)""")

/**
 * Строчная разметка: **жирный** (внутри — та же разметка), `код`, ссылки трёх видов — DeepSeek пишет [текст](адрес), Hermes —
 * голые адреса и <адрес>. Значок 🔗 перед ссылкой убираем: ссылка и так видна.
 */
internal fun mdInline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("🔗", i) && LINK_ICON.matchAt(s, i) != null -> { i += if (i + 2 < s.length && s[i + 2] == '️') 3 else 2; while (i < s.length && s[i] == ' ') i++ }
            s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> { val e = s.indexOf("**", i + 2); withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(mdInline(s.substring(i + 2, e))) }; i = e + 2 }
            s[i] == '`' && s.indexOf('`', i + 1) > 0 -> { val e = s.indexOf('`', i + 1); withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Ink.chipBg)) { append(s.substring(i + 1, e)) }; i = e + 1 }
            s[i] == '[' && MD_LINK.matchAt(s, i) != null -> { val m = MD_LINK.matchAt(s, i)!!; withLink(link(m.groupValues[2])) { append(mdInline(m.groupValues[1])) }; i += m.value.length }
            s[i] == '<' && ANGLE_LINK.matchAt(s, i) != null -> { val m = ANGLE_LINK.matchAt(s, i)!!; withLink(link(m.groupValues[1])) { append(m.groupValues[1]) }; i += m.value.length }
            s[i] == 'h' && BARE_LINK.matchAt(s, i) != null -> {
                val url = BARE_LINK.matchAt(s, i)!!.value.trimEnd('.', ',', ';', ':', '!', '?', '»', '"', '\'')
                withLink(link(url)) { append(url) }; i += url.length
            }
            else -> { append(s[i]); i++ }
        }
    }
}
