package com.nuclearhack.steno

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Тонкий слой над [AppState]: тяжёлая работа запускается в [ProcessingService]. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val state: StateFlow<UiState> = AppState.state

    init { Models.preload(app) }

    fun setNumSpeakers(n: Int) { if (!AppState.value.busy) AppState.update { it.copy(numSpeakers = n) } }

    fun transcribe(uri: Uri) {
        if (AppState.value.working) return
        // доступ к файлу нужен и сервису, и плееру после перезапуска
        runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = displayName(uri)
        AppState.update { UiState(fileName = name, audioUri = uri, busy = true, numSpeakers = it.numSpeakers) }
        ProcessingService.start(getApplication(), Intent(ProcessingService.ACTION_TRANSCRIBE, uri)
            .putExtra(ProcessingService.EXTRA_SPK, AppState.value.numSpeakers))
    }

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

    fun export(uri: Uri) = write(uri, TranscriptJson.build(AppState.value.utterances), "Сохранено")

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
