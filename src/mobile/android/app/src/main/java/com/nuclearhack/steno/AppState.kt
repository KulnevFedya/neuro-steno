package com.nuclearhack.steno

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UiState(
    val fileName: String? = null,
    val audioUri: Uri? = null,
    val busy: Boolean = false,
    val progress: Float = 0f,
    val utterances: List<Utterance> = emptyList(),
    val rtf: Double? = null,
    val numSpeakers: Int = -1,
    val lang: String = "ru",
    /** Пользовательские имена спикеров: только UI и TXT, в JSON остаётся speaker_id. */
    val speakerNames: Map<String, String> = emptyMap(),
    val error: String? = null,
    val message: String? = null,
    val summarizing: Boolean = false,
    val summaryStage: String = "",
    val summaryProgress: Float = 0f,
    val summary: SummaryLogic.Summary? = null,
    val summaryJson: String? = null,
) {
    val working get() = busy || summarizing
}

/**
 * Общее состояние процесса: его пишет [ProcessingService] (работа идёт и при свёрнутом приложении
 * и заблокированном экране), читает UI. Живёт, пока жив процесс.
 */
object AppState {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    val value get() = _state.value

    fun update(f: (UiState) -> UiState) = _state.update(f)
}
