package com.wren.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import util.DownloadPreference
/**
 * Whether SoundCloud tracks may be saved to disk, backed by [DownloadPreference].
 *
 * Global rather than screen state, so turning it off takes the button away everywhere at once
 * instead of leaving it on whichever screens were already composed.
 */
var allowSoundCloudDownloads by mutableStateOf(false)
