package ru.poneslas.vahter.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разметка ответа (Markdown.kt): блоки и строчная разметка — без экрана, на JVM. */
class MarkdownTest {
    /** Ссылки строки: (текст под ссылкой, адрес). */
    private fun links(a: AnnotatedString) = a.getLinkAnnotations(0, a.length)
        .map { a.text.substring(it.start, it.end) to (it.item as LinkAnnotation.Clickable).tag }

    @Test fun bold() {
        val a = mdInline("до **жирный** после")
        assertEquals("до жирный после", a.text)
        val s = a.spanStyles.single()
        assertEquals(FontWeight.SemiBold, s.item.fontWeight)
        assertEquals("жирный", a.text.substring(s.start, s.end))
    }

    @Test fun code() {
        val a = mdInline("запустите `restic init` сейчас")
        assertEquals("запустите restic init сейчас", a.text)
        val s = a.spanStyles.single()
        assertEquals(FontFamily.Monospace, s.item.fontFamily)
        assertEquals("restic init", a.text.substring(s.start, s.end))
    }

    @Test fun mdLink() {
        val a = mdInline("см. [сайт](https://example.ru/a) тут")
        assertEquals("см. сайт тут", a.text)
        assertEquals(listOf("сайт" to "https://example.ru/a"), links(a))
    }

    @Test fun mdLinkAngleAndTitle() {
        assertEquals(listOf("сайт" to "https://example.ru/a"), links(mdInline("[сайт](<https://example.ru/a>)")))
        val t = mdInline("[сайт](https://example.ru/a \"подсказка\")")
        assertEquals("сайт", t.text)
        assertEquals(listOf("сайт" to "https://example.ru/a"), links(t))
    }

    @Test fun bareLinkTrimsPunctuation() {
        val a = mdInline("Откройте https://example.ru/x?y=1. Дальше")
        assertEquals("Откройте https://example.ru/x?y=1. Дальше", a.text)
        assertEquals(listOf("https://example.ru/x?y=1" to "https://example.ru/x?y=1"), links(a))
    }

    @Test fun angleLink() {
        val a = mdInline("вот <https://example.ru/b>!")
        assertEquals("вот https://example.ru/b!", a.text)
        assertEquals(listOf("https://example.ru/b" to "https://example.ru/b"), links(a))
    }

    @Test fun linkIconRemoved() {
        assertEquals("сайт", mdInline("🔗 [сайт](https://example.ru)").text)
        assertEquals("https://example.ru", mdInline("🔗️  https://example.ru").text)
        assertEquals("🔗 просто", mdInline("🔗 просто").text)
    }

    @Test fun blocks() {
        val b = mdBlocks("# Заголовок\nабзац\nпродолжение\n\n- пункт\n1. первый\n```\nкод\n```\n| a | b |\n|---|---|\n| 1 | 2 |")
        assertEquals(listOf(
            'h' to "Заголовок",
            'p' to "абзац продолжение",
            'l' to "пункт",
            'n' to "1. первый",
            'c' to "код",
            't' to "| a | b |\n|---|---|\n| 1 | 2 |",
        ), b)
    }

    @Test fun tableAfterParagraph() {
        val b = mdBlocks("текст\n| a |\n| b |")
        assertEquals(listOf('p' to "текст", 't' to "| a |\n| b |"), b)
        assertTrue(mdBlocks("## Шаги").single() == ('h' to "Шаги"))
    }
}
