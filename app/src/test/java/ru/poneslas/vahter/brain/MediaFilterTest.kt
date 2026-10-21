package ru.poneslas.vahter.brain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Теги MEDIA: в потоке Hermes приходят кусками по буквам — фильтр не должен ни показать тег, ни задержать обычный текст. */
class MediaFilterTest {
    private suspend fun run(chunks: List<String>): Pair<String, List<String>> {
        val text = StringBuilder(); val media = mutableListOf<String>()
        val f = MediaFilter({ text.append(it) }, { media += it })
        chunks.forEach { f.feed(it) }
        f.finish()
        return text.toString() to media
    }

    @Test fun tagsLeaveTextAndArriveAsMedia() = runTest {
        val (t, m) = run(listOf("Готово.\n\nME", "DIA:/opt/data/vahter-out/sine.png\nMEDIA:/opt/data/vahter-out/report.txt", "\nЭто график."))
        assertEquals("Готово.\n\nЭто график.", t)
        assertEquals(listOf("/opt/data/vahter-out/sine.png", "/opt/data/vahter-out/report.txt"), m)
    }

    @Test fun plainTextIsNotHeldBack() = runTest {
        val text = StringBuilder()
        val f = MediaFilter({ text.append(it) }, { })
        f.feed("Прив"); assertEquals("Прив", text.toString())
        f.feed("ет, M"); assertEquals("Привет, M", text.toString())
        f.feed("осква\n"); assertEquals("Привет, Mосква\n", text.toString())
    }

    @Test fun tagWithoutNewlineAtEnd() = runTest {
        val (t, m) = run(listOf("Файл:\nMEDIA:/x/vahter-out/a.pdf"))
        assertEquals("Файл:\n", t)
        assertEquals(listOf("/x/vahter-out/a.pdf"), m)
    }
}
