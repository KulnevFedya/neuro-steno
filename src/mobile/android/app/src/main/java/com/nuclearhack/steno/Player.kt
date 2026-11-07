package com.nuclearhack.steno

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerState(val ready: Boolean = false, val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0)

/** Воспроизведение исходной записи; позиция обновляется ~5 раз в секунду (подсветка текущей реплики). */
class Player(private val context: Context, private val scope: CoroutineScope) {
    private var mp: MediaPlayer? = null
    private var uri: Uri? = null
    private var ticker: Job? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Готовит плеер для [u] (повторный вызов с тем же URI ничего не делает). */
    fun open(u: Uri?) {
        if (u == uri) return
        release()
        uri = u
        if (u == null) return
        try {
            mp = MediaPlayer().apply {
                setDataSource(context, u)
                setOnCompletionListener { _state.value = _state.value.copy(playing = false, positionMs = _state.value.durationMs); ticker?.cancel() }
                prepare()
            }
            _state.value = PlayerState(ready = true, durationMs = mp!!.duration.toLong())
        } catch (t: Throwable) {
            Log.w(TAG, "player: cannot open $u", t)
            mp?.release(); mp = null
            _state.value = PlayerState()
        }
    }

    fun toggle() {
        val p = mp ?: return
        if (p.isPlaying) pause() else play()
    }

    fun play() {
        val p = mp ?: return
        p.start()
        _state.value = _state.value.copy(playing = true)
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                _state.value = _state.value.copy(positionMs = p.currentPosition.toLong())
                delay(200)
            }
        }
    }

    fun pause() {
        val p = mp ?: return
        if (p.isPlaying) p.pause()
        ticker?.cancel()
        _state.value = _state.value.copy(playing = false, positionMs = p.currentPosition.toLong())
    }

    fun seekTo(ms: Long, andPlay: Boolean = false) {
        val p = mp ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) p.seekTo(ms, MediaPlayer.SEEK_CLOSEST) else p.seekTo(ms.toInt())
        _state.value = _state.value.copy(positionMs = ms)
        if (andPlay && !p.isPlaying) play()
    }

    fun release() {
        ticker?.cancel()
        mp?.release(); mp = null; uri = null
        _state.value = PlayerState()
    }
}
