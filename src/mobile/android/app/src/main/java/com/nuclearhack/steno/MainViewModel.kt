package com.nuclearhack.steno

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
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
    val error: String? = null,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun transcribe(uri: Uri) {
        if (_state.value.busy) return
        val name = displayName(uri)
        _state.value = UiState(fileName = name, busy = true)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val r = Transcriber(getApplication()).run(uri) { p -> _state.value = _state.value.copy(progress = p) }
                _state.value = _state.value.copy(busy = false, progress = 1f, utterances = r.utterances, rtf = r.rtf)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "transcribe failed", t)
                _state.value = _state.value.copy(busy = false, error = t.message ?: t.toString())
            }
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
