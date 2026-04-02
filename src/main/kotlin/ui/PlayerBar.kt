package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import player.MpvPlayer

@Composable
fun PlayerBar(player: MpvPlayer) {
    val isPlaying by player.isPlaying
    val isLoading by player.isLoading
    val position by player.position
    val duration by player.duration
    val volume by player.volume
    val currentId by player.currentTitle
    val currentDisplayTitle by player.displayTitle
    val queue by player.queue
    val queueIndex by player.queueIndex
    val hasPrevious = queueIndex > 0
    val hasNext = queueIndex >= 0 && queueIndex < queue.size - 1

    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableStateOf(0f) }

    val progress = if (duration > 0.0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 24.dp, vertical = 10.dp)
    ) {
        // Loading bar or seek slider
        Box(Modifier.fillMaxWidth().height(24.dp)) {
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                    color = Accent.copy(alpha = 0.5f),
                    backgroundColor = Color(0xFF333333)
                )
            } else {
                Slider(
                    value = if (seeking) seekValue else progress,
                    onValueChange = { seeking = true; seekValue = it },
                    onValueChangeFinished = {
                        player.seek(seekValue.toDouble() * duration)
                        seeking = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Color(0xFF333333)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Elapsed time
            Text(
                formatTime(if (seeking) (seekValue * duration).toLong() else position.toLong()),
                color = TextSecondary, fontSize = 11.sp,
                modifier = Modifier.width(36.dp)
            )

            // Track title centered — reads from displayTitle, not currentTitle
            Text(
                if (currentDisplayTitle.isNotEmpty()) currentDisplayTitle else "—",
                color = if (currentDisplayTitle.isNotEmpty()) TextPrimary else TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            )

            // Total duration
            Text(
                formatTime(duration.toLong()),
                color = TextSecondary, fontSize = 11.sp,
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.End
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Volume control
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.width(180.dp)
            ) {
                Icon(
                    if (volume > 0) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    contentDescription = null, tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { player.setVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = TextSecondary,
                        activeTrackColor = TextSecondary,
                        inactiveTrackColor = Color(0xFF333333)
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { player.previous() },
                    enabled = hasPrevious,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Anterior",
                        tint = if (hasPrevious) TextPrimary else TextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = Accent,
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(
                            onClick = { if (currentId.isNotEmpty()) player.playPause() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (currentId.isNotEmpty()) TextPrimary else TextSecondary,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { player.next() },
                    enabled = hasNext,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Siguiente",
                        tint = if (hasNext) TextPrimary else TextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.width(180.dp))
        }
    }
}

private fun formatTime(seconds: Long): String {
    if (seconds <= 0L) return "0:00"
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
