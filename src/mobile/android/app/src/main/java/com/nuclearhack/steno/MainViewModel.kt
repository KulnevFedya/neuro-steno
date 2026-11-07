package com.nuclearhack.steno

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val fileName: String? = null,
    val busy: Boolean = false,
    val progress: Float = 0f,
    val utterances: List<Utterance> = emptyList(),
    val rtf: Double? = null,
    val numSpeakers: Int = -1,
    val error: String? = null,
    val message: String? = null,
    val summarizing: Boolean = false,
    val summaryStage: String = "",
    val summaryProgress: Float = 0f,
    val summary: SummaryLogic.Summary? = null,
    val summaryJson: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { Models.preload(app) }

    fun setNumSpeakers(n: Int) { if (!_state.value.busy) _state.value = _state.value.copy(numSpeakers = n) }

    fun transcribe(uri: Uri) {
        if (_state.value.busy || _state.value.summarizing) return
        val name = displayName(uri)
        val n = _state.value.numSpeakers
        _state.value = UiState(fileName = name, busy = true, numSpeakers = n)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val r = Transcriber(getApplication()).run(uri, n) { p -> _state.value = _state.value.copy(progress = p) }
                _state.value = _state.value.copy(busy = false, progress = 1f, utterances = r.utterances, rtf = r.rtf)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "transcribe failed", t)
                _state.value = _state.value.copy(busy = false, error = t.message ?: t.toString())
            }
        }
    }

    fun summarize() {
        val st = _state.value
        if (st.busy || st.summarizing || st.utterances.isEmpty()) return
        _state.value = st.copy(summarizing = true, summary = null, summaryJson = null, summaryStage = "load", summaryProgress = 0f, error = null)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val r = Summarizer(getApplication()).run(st.utterances) { stage, p ->
                    _state.value = _state.value.copy(summaryStage = stage, summaryProgress = p)
                }
                // копия рядом с приложением (доступна по adb/файловому менеджеру), экспорт через SAF — кнопкой
                runCatching {
                    val dir = File(getApplication<Application>().getExternalFilesDir(null), "summaries").apply { mkdirs() }
                    File(dir, SummaryLogic.summaryName(st.fileName ?: "transcript")).writeText(r.json)
                }
                _state.value = _state.value.copy(summarizing = false, summary = r.summary, summaryJson = r.json)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "summary failed", t)
                _state.value = _state.value.copy(summarizing = false, error = "Резюме: ${t.message ?: t}")
            } finally {
                Models.preload(getApplication()) // модели ASR/диаризации возвращаем в фоне для следующей записи
            }
        }
    }

    fun summaryName(): String = SummaryLogic.summaryName(_state.value.fileName ?: "transcript")

    fun exportSummary(uri: Uri) {
        val json = _state.value.summaryJson ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                "Резюме сохранено"
            } catch (t: Throwable) { "Ошибка экспорта: ${t.message}" }
            withContext(Dispatchers.Main) { _state.value = _state.value.copy(message = msg) }
        }
    }

    fun exportName(): String = TranscriptJson.exportName(_state.value.fileName ?: "transcript")

    fun export(uri: Uri) {
        val json = TranscriptJson.build(_state.value.utterances)
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                }
                "Сохранено"
            } catch (t: Throwable) {
                "Ошибка экспорта: ${t.message}"
            }
            withContext(Dispatchers.Main) { _state.value = _state.value.copy(message = msg) }
        }
    }

    fun messageShown() { _state.value = _state.value.copy(message = null) }

    private fun displayName(uri: Uri): String {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        return uri.lastPathSegment ?: "audio"
    }
}
