package com.nuclearhack.steno

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Foreground service для тяжёлой работы: расшифровка, резюме, пакетный прогон (eval).
 * Уведомление с прогрессом + partial wake lock — обработка переживает сворачивание и блокировку экрана.
 * Задачи выполняются по одной (однопоточный executor); результат — в [AppState].
 */
class ProcessingService : Service() {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "processing") }
    private var pending = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L
    private var lastPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Обработка записей", NotificationManager.IMPORTANCE_LOW))
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "steno:processing").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg(notification("Подготовка…", 0))
        wakeLock?.acquire(6 * 60 * 60 * 1000L)
        val job: (() -> Unit)? = when (intent?.action) {
            ACTION_TRANSCRIBE -> {
                val uri = intent.data!!
                val spk = intent.getIntExtra(EXTRA_SPK, -1)
                val lang = intent.getStringExtra(EXTRA_LANG) ?: "ru"
                ({ transcribe(uri, spk, lang) })
            }
            ACTION_SUMMARIZE -> ({ summarize() })
            ACTION_BATCH -> {
                val input = intent.getStringExtra(EXTRA_IN)!!
                val spk = intent.getIntExtra(EXTRA_SPK, -1)
                val withSummary = intent.getBooleanExtra(EXTRA_SUMMARY, false)
                val lang = intent.getStringExtra(EXTRA_LANG) ?: "ru"
                ({ batch(File(input), spk, lang, withSummary) })
            }
            else -> null
        }
        if (job == null) { finishIfIdle(); return START_NOT_STICKY }
        synchronized(this) { pending++ }
        worker.execute {
            try { job() } catch (t: Throwable) { Log.e(TAG, "job failed", t) }
            finally { synchronized(this) { pending-- }; finishIfIdle() }
        }
        return START_NOT_STICKY
    }

    private fun finishIfIdle() {
        synchronized(this) { if (pending > 0) return }
        wakeLock?.let { if (it.isHeld) it.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---- задачи ----

    private fun transcribe(uri: Uri, spk: Int, lang: String) {
        val name = AppState.value.fileName ?: "audio"
        try {
            val r = Transcriber(this).run(uri, spk, lang) { p ->
                AppState.update { it.copy(progress = p) }
                progress("Расшифровка: $name", p)
            }
            AppState.update { it.copy(busy = false, progress = 1f, utterances = r.utterances, rtf = r.rtf) }
            done("Готово: $name", "${r.utterances.size} реплик, спикеров: ${r.numSpeakers}")
        } catch (t: Throwable) {
            Log.e(TAG, "transcribe failed", t)
            val msg = userError(t)
            AppState.update { it.copy(busy = false, error = msg) }
            done("Ошибка: $name", msg)
        }
    }

    private fun summarize() {
        val st = AppState.value
        try {
            val r = Summarizer(this).run(st.utterances) { stage, p ->
                AppState.update { it.copy(summaryStage = stage, summaryProgress = p) }
                progress("Резюме: ${summaryStageLabel(stage)}", p)
            }
            // копия рядом с приложением (доступна по adb/файловому менеджеру), экспорт через SAF — кнопкой
            runCatching {
                val dir = File(getExternalFilesDir(null), "summaries").apply { mkdirs() }
                File(dir, SummaryLogic.summaryName(st.fileName ?: "transcript")).writeText(r.json)
            }
            AppState.update { it.copy(summarizing = false, summary = r.summary, summaryJson = r.json) }
            done("Резюме готово", st.fileName ?: "")
        } catch (t: Throwable) {
            Log.e(TAG, "summary failed", t)
            AppState.update { it.copy(summarizing = false, error = "Резюме: ${t.message ?: t}") }
            done("Ошибка резюме", t.message ?: t.toString())
        } finally {
            Models.preload(this) // модели ASR/диаризации возвращаем в фоне для следующей записи
        }
    }

    /** Пакетный прогон для eval: каждый файл → out/<имя>.json + .meta.json; в конце out/DONE. */
    private fun batch(input: File, spk: Int, lang: String, withSummary: Boolean) {
        val outDir = File(getExternalFilesDir(null), "out").apply { mkdirs() }
        val files = if (input.isDirectory) (input.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name } else listOf(input)
        Models.await(this) // как в приложении: модели уже загружены к нажатию «Запуск»
        for ((i, f) in files.withIndex()) {
            try {
                progress("Пакет ${i + 1}/${files.size}: ${f.name}", i.toFloat() / files.size)
                if (f.extension == "json") { // готовая расшифровка -> только резюме (быстрая отладка промптов)
                    val sr = Summarizer(this).run(TranscriptJson.parse(f.readText()))
                    File(outDir, "${f.nameWithoutExtension}.summary.json").writeText(sr.json)
                    continue
                }
                val r = Transcriber(this).run(Uri.fromFile(f), spk, lang) { p ->
                    progress("Пакет ${i + 1}/${files.size}: ${f.name}", (i + p) / files.size)
                }
                val name = f.nameWithoutExtension
                File(outDir, "$name.json").writeText(TranscriptJson.build(r.utterances))
                if (withSummary) {
                    val sr = Summarizer(this).run(r.utterances)
                    File(outDir, "$name.summary.json").writeText(sr.json)
                    Models.await(this) // вернуть модели для следующего файла
                }
                File(outDir, "$name.meta.json").writeText(
                    "{\"speakers\": ${r.numSpeakers}, \"audio_ms\": ${r.audioMs}, \"proc_ms\": ${r.decodeAsrMs}, \"rtf\": ${r.rtf}}\n"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "batch failed for ${f.name}", t)
                File(outDir, "${f.nameWithoutExtension}.error").writeText(t.stackTraceToString())
            }
        }
        File(outDir, "DONE").writeText("ok")
        done("Пакет готов", "${files.size} файл(ов)")
    }

    // ---- уведомления ----

    private fun startFg(n: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    /** Обновление прогресса не чаще раза в 0.5 с и только при смене процента. */
    private fun progress(title: String, p: Float) {
        val pct = (p * 100).toInt().coerceIn(0, 100)
        val now = SystemClock.elapsedRealtime()
        if (pct == lastPercent || now - lastNotify < 500) return
        lastPercent = pct; lastNotify = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(title, pct))
    }

    /** Итоговое уведомление (отдельный id — остаётся после остановки сервиса). */
    private fun done(title: String, text: String) {
        lastPercent = -1
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(openApp()).setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
    }

    private fun notification(title: String, pct: Int) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(title)
        .setContentText("$pct%")
        .setProgress(100, pct, false)
        .setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(openApp())
        .build()

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL = "processing"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2
        const val ACTION_TRANSCRIBE = "steno.TRANSCRIBE"
        const val ACTION_SUMMARIZE = "steno.SUMMARIZE"
        const val ACTION_BATCH = "steno.BATCH"
        const val EXTRA_SPK = "spk"
        const val EXTRA_IN = "in"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_LANG = "lang"

        fun start(context: Context, intent: Intent) {
            intent.setClass(context, ProcessingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun summaryStageLabel(stage: String) = when (stage) {
            "copy" -> "копирование модели (первый запуск)"
            "load" -> "загрузка модели"
            "map" -> "конспект частей"
            "reduce" -> "сжатие конспектов"
            else -> "итоги"
        }

        /** Понятное сообщение для пользователя вместо стектрейса. */
        fun userError(t: Throwable): String {
            val m = t.message.orEmpty()
            return when {
                t is UserFacingException -> m
                t is OutOfMemoryError -> "Недостаточно памяти для обработки файла"
                t is java.io.FileNotFoundException || t is SecurityException -> "Не удалось открыть файл (нет доступа или файл удалён)"
                t is java.io.IOException || t is IllegalArgumentException || t is IllegalStateException && m.contains("codec", true) ||
                    t is android.media.MediaCodec.CodecException ->
                    "Файл повреждён или формат не поддерживается"
                else -> "Не удалось обработать файл: ${m.ifBlank { t.javaClass.simpleName }}"
            }
        }
    }
}
