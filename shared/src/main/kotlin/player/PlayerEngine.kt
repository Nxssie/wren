package player

import kotlinx.coroutines.flow.StateFlow
import models.QueueItem
import models.RepeatMode

/**
 * Transport + observable state contract shared by every platform player. Android's
 * [com.wren.app.player.ExoPlayerEngine] is the first implementation; the desktop
 * FFmpegPlayer keeps its Compose-state API so the shipping app is untouched.
 */
interface PlayerEngine {
    val isPlaying: StateFlow<Boolean>
    val isLoading: StateFlow<Boolean>
    val isPaused: StateFlow<Boolean>
    val isEnqueuing: StateFlow<Boolean>
    val position: StateFlow<Double>
    val duration: StateFlow<Double>
    val volume: StateFlow<Int>
    val currentTitle: StateFlow<String>
    val displayTitle: StateFlow<String>
    val queue: StateFlow<List<QueueItem>>
    val queueIndex: StateFlow<Int>
    /** Where the queue came from (playlist, mix, search…), for a "playing from" caption. */
    val queueTitle: StateFlow<String?>
    val shuffle: StateFlow<Boolean>
    val repeatMode: StateFlow<RepeatMode>

    fun start()
    fun stop()
    fun load(url: String, videoId: String, title: String = "")
    fun loadQueue(items: List<QueueItem>, startIndex: Int = 0, title: String? = null)
    fun jumpTo(index: Int)
    fun clearQueue()
    fun toggleShuffle()
    fun toggleRepeat()
    fun next()
    fun previous()
    fun playPause()
    fun seek(seconds: Double)
    fun setVolume(vol: Int)
}
