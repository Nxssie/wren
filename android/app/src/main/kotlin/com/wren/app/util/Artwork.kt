package com.wren.app.util

import models.QueueItem
import models.Source

/** YouTube thumbnails are derivable from the video id; SoundCloud ones come in the item. */
fun artworkFor(item: QueueItem?): String? {
    if (item == null) return null
    item.artworkUrl?.let { return it }
    return when (item.source) {
        Source.SOUNDCLOUD -> null
        else -> "https://i.ytimg.com/vi/${item.videoId}/hqdefault.jpg"
    }
}
