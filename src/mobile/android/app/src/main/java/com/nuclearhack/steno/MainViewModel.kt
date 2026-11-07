package com.nuclearhack.steno

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecState(val recording: Boolean = false, val ms: Long = 0, val level: Float = 0f)

/** Тонкий слой над [AppState]: тяжёлая работа запускается в [ProcessingService]; плеер и запись — здесь. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val state: StateFlow<UiState> = AppState.state
    val player = Player(app, viewModelScope)
    private var recorder: Recorder? = null
    private var recTicker: Job? = null
    private val _rec = MutableStateFlow(RecState())
    val rec: StateFlow<RecState> = _rec.asStateFlow()

    init {
        Models.preload(app)
        // плеер открывается, когда для записи готова расшифровка
        viewModelScope.launch {
            AppState.state.map { if (it.utterances.isNotEmpty() && !it.busy) it.audioUri else null }
                .distinctUntilChanged()
                .collect { u -> withContext(Dispatchers.IO) { player.open(u) } }
        }
    }

    fun setNumSpeakers(n: Int) { if (!AppState.value.busy) AppState.update { it.copy(numSpeakers = n) } }

    fun setLang(l: String) {
        if (AppState.value.working || AppState.value.lang == l) return
        AppState.update { it.copy(lang = l) }
        Models.switchLang(getApplication(), l)
    }

    fun renameSpeaker(id: String, name: String) {
        AppState.update { it.copy(speakerNames = it.speakerNames + (id to name.trim())) }
    }

    fun transcribe(uri: Uri, name: String? = null) {
        if (AppState.value.working || _rec.value.recording) return
        player.release()
        // доступ к файлу нужен и сервису, и плееру после перезапуска
        runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val fileName = name ?: displayName(uri)
        AppState.update { UiState(fileName = fileName, audioUri = uri, busy = true, numSpeakers = it.numSpeakers, lang = it.lang) }
        ProcessingService.start(getApplication(), Intent(ProcessingService.ACTION_TRANSCRIBE, uri)
            .putExtra(ProcessingService.EXTRA_SPK, AppState.value.numSpeakers)
            .putExtra(ProcessingService.EXTRA_LANG, AppState.value.lang))
    }

    /** «Новая расшифровка»: вернуться на стартовый экран (настройки языка и спикеров сохраняются). */
    fun newTranscript() {
        if (AppState.value.working || _rec.value.recording) return
        player.release()
        AppState.update { UiState(numSpeakers = it.numSpeakers, lang = it.lang) }
    }

    // ---- запись с микрофона ----

    fun startRecording() {
        if (AppState.value.working || _rec.value.recording) return
        player.pause()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(getApplication<Application>().getExternalFilesDir(null), "recordings/rec_$stamp.wav")
        val r = Recorder(file)
        try { r.start() } catch (t: Throwable) {
            AppState.update { it.copy(error = ProcessingService.userError(t)) }
            return
        }
        recorder = r
        _rec.value = RecState(recording = true)
        recTicker = viewModelScope.launch {
            while (isActive) { _rec.value = RecState(true, r.durationMs, r.level); delay(100) }
        }
    }

    /** Остановить запись и сразу расшифровать (тот же пайплайн, что и для импорта). */
    fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        recTicker?.cancel()
        _rec.value = RecState()
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) { r.stop() }
            if (r.durationMs < 500) { AppState.update { it.copy(error = "Запись слишком короткая") }; f.delete(); return@launch }
            transcribe(Uri.fromFile(f), f.name)
        }
    }

    // ---- резюме и экспорт ----

    fun summarize() {
        val st = AppState.value
        if (st.working || st.utterances.isEmpty()) return
        AppState.update { it.copy(summarizing = true, summary = null, summaryJson = null, summaryStage = "load", summaryProgress = 0f, error = null) }
        ProcessingService.start(getApplication(), Intent(ProcessingService.ACTION_SUMMARIZE))
    }

    fun summaryName(): String = SummaryLogic.summaryName(AppState.value.fileName ?: "transcript")

    fun exportSummary(uri: Uri) {
        val json = AppState.value.summaryJson ?: return
        write(uri, json, "Резюме сохранено")
    }

    fun exportName(): String = TranscriptJson.exportName(AppState.value.fileName ?: "transcript")
    fun exportTxtName(): String = TranscriptText.exportName(AppState.value.fileName ?: "transcript")

    fun export(uri: Uri) = write(uri, TranscriptJson.build(AppState.value.utterances), "JSON сохранён")

    fun exportTxt(uri: Uri) = AppState.value.let { write(uri, TranscriptText.build(it.utterances, it.speakerNames), "TXT сохранён") }

    private fun write(uri: Uri, text: String, ok: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ok
            } catch (t: Throwable) {
                "Ошибка экспорта: ${t.message}"
            }
            AppState.update { it.copy(message = msg) }
        }
    }

    fun messageShown() { AppState.update { it.copy(message = null) } }
    fun errorShown() { AppState.update { it.copy(error = null) } }

    override fun onCleared() {
        recorder?.stop()
        player.release()
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) return it.getString(0)
                }
        }
        return uri.lastPathSegment ?: "audio"
    }
}
