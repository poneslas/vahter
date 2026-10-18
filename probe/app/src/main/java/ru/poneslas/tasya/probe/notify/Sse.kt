package ru.poneslas.tasya.probe.notify

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.poneslas.tasya.probe.TAG
import ru.poneslas.tasya.probe.core.Config
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
     * Поток без своего сервера: httpbin.org/drip отдаёт [seconds] байт по одному в секунду —
     * как ответ, который приходит частями. Один байт = одна «часть».
     */
    private fun readDrip(ctx: Context, run: Run, seconds: Int, cancelled: () -> Boolean): Boolean {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://httpbin.org/drip?duration=$seconds&numbytes=$seconds&delay=0").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            val input = conn.inputStream
            var lastAt = SystemClock.uptimeMillis()
            while (!cancelled()) {
                val b = input.read()
                if (b < 0) break
                val now = SystemClock.uptimeMillis()
                if (run.events > 0) run.maxGapMs = maxOf(run.maxGapMs, now - lastAt)
                lastAt = now
                run.events++
            }
            run.done = run.events >= seconds
            if (!run.done) run.error = "поток оборвался на ${run.events} из $seconds"
        } catch (t: Throwable) {
            run.error = "${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "drip ${run.id}", t)
        } finally {
            conn?.disconnect()
            run.finished = true
            run.finishedAt = SystemClock.uptimeMillis()
        }
        if (run.done) run.notified = AnswerNotifier.postAnswer(ctx, "Ответ готов (${run.variant}): ${run.events} частей")
        return run.done
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
