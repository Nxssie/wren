package com.wren.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wren.app.util.artworkFor
import androidx.compose.ui.unit.sp
import player.PlayerEngine

/** Compact transport pinned above the bottom navigation; tap opens Now Playing. */
@Composable
fun PlayerBar(engine: PlayerEngine, onOpen: () -> Unit) {
    val queue by engine.queue.collectAsState()
    val index by engine.queueIndex.collectAsState()
    val isPlaying by engine.isPlaying.collectAsState()
    val position by engine.position.collectAsState()
    val duration by engine.duration.collectAsState()

    val item = queue.getOrNull(index)
    val progress = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface)
            .clickable(onClick = onOpen)
    ) {
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = PsIrisCyan,
            backgroundColor = HairlineSoft,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(artworkFor(item), Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item?.title?.ifBlank { item?.videoId.orEmpty() } ?: "nothing playing",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item?.artist.orEmpty(),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { engine.playPause() }) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = TextPrimary,
                )
            }
            IconButton(onClick = { engine.next() }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = TextPrimary)
            }
        }
    }
}
