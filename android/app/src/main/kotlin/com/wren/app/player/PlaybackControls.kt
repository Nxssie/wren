package com.wren.app.player

/** What the playback notification needs to render, plus its transport actions. */
interface PlaybackControls {
    val notificationTitle: String
    val notificationSubtitle: String
    val notificationPlaying: Boolean

    /** Seconds elapsed in the current item, or 0 when unknown. */
    val notificationPosition: Double

    /** Total seconds of the current item, or 0 when unknown. */
    val notificationDuration: Double

    /** Album art URL of the current item, or null when it has none. */
    val notificationArtwork: String?

    fun onToggle()
    fun onNext()
    fun onPrevious()
    fun onSeek(seconds: Double)
}
