package ru.poneslas.vahter.probe.notify

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.poneslas.vahter.probe.TAG
import ru.poneslas.vahter.probe.core.Config
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Поток SSE с `scripts/serve.py /sse` — заменитель стрима ответа мозга (П23).
 * Дочитали до `event: done` — показываем уведомление «ответ готов».
 */
object Sse {
    class Run(val id: String, val variant: String) {
        val startedAt = SystemClock.uptimeMillis()
        @Volatile var events = 0
        @Volatile var skipped = 0
        @Volatile var maxGapMs = 0L
        @Volatile var done = false
        @Volatile var finished = false
        @Volatile var error: String? = null
        @Volatile var finishedAt: Long? = null
        @Volatile var notified = false
    }

    val runs = ConcurrentHashMap<String, Run>()

    fun start(id: String, variant: String): Run = Run(id, variant).also { runs[id] = it }

    /**
     * Ответ без своего сервера: [seconds] коротких запросов подряд, по одному в секунду, к
     * connectivitycheck.gstatic.com/generate_204 — им сам Android проверяет интернет, он надёжен.
     * httpbin.org оказался ненадёжным (19.10): медленный поток обрывался через ~17 с и на S24, и на эмуляторе.
     * Короткие запросы проверяют главное: процесс жив и ходит в сеть после закрытия листа.
     * Одна часть = один успешный запрос; ошибки отдельных запросов считаются пропусками.
     */
    private fun readDrip(ctx: Context, run: Run, seconds: Int, cancelled: () -> Boolean): Boolean {
        var lastAt = SystemClock.uptimeMillis()
        var fails = 0
        try {
            for (i in 1..seconds) {
                if (cancelled()) break
                val t0 = SystemClock.uptimeMillis()
                val ok = runCatching {
                    val c = URL("https://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection
                    c.connectTimeout = 8_000
                    c.readTimeout = 8_000
                    c.useCaches = false
                    try { c.responseCode == 204 } finally { c.disconnect() }
                }.onFailure { if (run.error == null) run.error = "${it.javaClass.simpleName}: ${it.message}" }.getOrDefault(false)
                val now = SystemClock.uptimeMillis()
                if (ok) {
                    if (run.events > 0) run.maxGapMs = maxOf(run.maxGapMs, now - lastAt)
                    lastAt = now
                    run.events++
                } else {
                    fails++
                    run.skipped++
                }
                Thread.sleep((1000 - (now - t0)).coerceAtLeast(0))
            }
            run.done = run.events >= seconds * 9 / 10
            if (!run.done && run.error == null) run.error = "успешных запросов ${run.events} из $seconds"
        } finally {
            run.finished = true
            run.finishedAt = SystemClock.uptimeMillis()
        }
        Log.i(TAG, "requests ${run.id}: успешно ${run.events} из $seconds, ошибок $fails")
        if (run.done) run.notified = AnswerNotifier.postAnswer(ctx, "Ответ готов (${run.variant}): ${run.events} частей")
        return run.done
    }

    /** Контроль: один медленный поток (httpbin drip) — обрывается ли он сам по себе, без закрытия листа. */
    fun readLongStream(seconds: Int): Pair<Int, String?> {
        var n = 0
        var err: String? = null
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://httpbin.org/drip?duration=$seconds&numbytes=$seconds&delay=0").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            val input = conn.inputStream
            while (input.read() >= 0) n++
        } catch (t: Throwable) {
            err = "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            conn?.disconnect()
        }
        return n to err
    }

    /** Блокирующее чтение потока. [seconds] — длина ответа на сервере. */
    fun readBlocking(ctx: Context, id: String, seconds: Int, cancelled: () -> Boolean = { false }): Boolean {
        val run = runs[id] ?: start(id, "?")
        if (!Config.useOwnServer) return readDrip(ctx, run, seconds, cancelled)
        var conn: HttpURLConnection? = null
        try {
            conn = URL("${Config.server}/sse?seconds=$seconds&interval=1000").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "text/event-stream")
            val reader = conn.inputStream.bufferedReader()
            var lastN = 0
            var lastAt = SystemClock.uptimeMillis()
            var event = ""
            while (!cancelled()) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val now = SystemClock.uptimeMillis()
                        val n = runCatching { JSONObject(line.removePrefix("data:").trim()).getInt("n") }.getOrDefault(lastN + 1)
                        if (event == "done") {
                            run.done = true
                        } else {
                            run.events++
                            if (n > lastN + 1) run.skipped += n - lastN - 1
                            if (lastN > 0) run.maxGapMs = maxOf(run.maxGapMs, now - lastAt)
                            lastN = n
                            lastAt = now
                        }
                    }
                    line.isEmpty() -> event = ""
                }
                if (run.done) break
            }
        } catch (t: Throwable) {
            run.error = "${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "sse $id", t)
        } finally {
            conn?.disconnect()
            run.finished = true
            run.finishedAt = SystemClock.uptimeMillis()
        }
        Log.i(TAG, "sse $id: событий ${run.events}, done=${run.done}, ошибка=${run.error}")
        if (run.done) {
            run.notified = AnswerNotifier.postAnswer(ctx, "Ответ готов (${run.variant}): ${run.events} частей")
        }
        return run.done
    }
}

/** Вариант 2: ускоренная задача WorkManager. */
class SseWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: "work"
        val seconds = inputData.getInt("seconds", 90)
        val ok = withContext(Dispatchers.IO) { Sse.readBlocking(applicationContext, id, seconds) { isStopped } }
        if (isStopped) Sse.runs[id]?.error = "WorkManager остановил: stopReason=$stopReason"
        return if (ok) Result.success() else Result.failure()
    }
}
