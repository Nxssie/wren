package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import player.FFmpegPlayer
import player.RepeatMode

@Composable
fun PlayerBar(player: FFmpegPlayer) {
    val isPlaying by player.isPlaying
    val isLoading by player.isLoading
    val position by player.position
    val duration by player.duration
    val volume by player.volume
    val currentId by player.currentTitle
    val queue by player.queue
    val queueIndex by player.queueIndex
    val isShuffle by player.shuffle
    val repeatMode by player.repeatMode
    val isPaused by player.isPaused
    val hasPrevious = queueIndex > 0
    val hasNext = queueIndex >= 0 && queueIndex < queue.size - 1

    // Seek state
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableStateOf(0f) }
    val progress = if (duration > 0.0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f

    // Mute state
    var isMuted by remember { mutableStateOf(false) }
    val previousVolume = remember { mutableStateOf(50) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface)
            .drawBehind {
                drawLine(
                    color = if (globalDark) PsWhite.copy(alpha = 0.12f) else PsInk900,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1f
                )
            }
            .padding(horizontal = 24.dp, vertical = 10.dp)
    ) {
        // ── Row 1: seek slider framed by time labels ──
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Elapsed time — left of seek
            Text(
                formatTime(if (seeking) (seekValue * duration).toLong() else position.toLong()),
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontMono,
                modifier = Modifier.width(36.dp)
            )

            // Seek slider + loading indicator
            Box(Modifier.weight(1f).height(20.dp)) {
                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                        color = PsSteel400,
                        backgroundColor = PsInset
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
                            inactiveTrackColor = PsInset,
                        ),
                        modifier = Modifier.fillMaxWidth().height(20.dp)
                    )
                }
            }

            // Total duration — right of seek
            Text(
                formatTime(duration.toLong()),
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontMono,
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.End
            )
        }

        // ── Row 2: compact controls ──
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left zone: shuffle toggle
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                PsToggle(
                    label = "_shuffle;",
                    active = isShuffle,
                    onClick = { player.toggleShuffle() },
                    icon = {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (isShuffle) Accent else TextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }

            // Center zone: transport controls
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { player.previous() },
                    enabled = hasPrevious,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = if (hasPrevious) Accent else TextSecondary.copy(alpha = 0.4f),
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
                                if (isPlaying && !isPaused) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (currentId.isNotEmpty()) Accent else TextSecondary,
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
                        contentDescription = "Next",
                        tint = if (hasNext) Accent else TextSecondary.copy(alpha = 0.4f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Right zone: repeat + volume
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Repeat toggle
                    PsToggle(
                        label = "_repeat;",
                        active = repeatMode != RepeatMode.OFF,
                        onClick = { player.toggleRepeat() },
                        icon = {
                            val repeatIcon = when (repeatMode) {
                                RepeatMode.SINGLE -> Icons.Default.RepeatOne
                                RepeatMode.ALL -> Icons.Default.Repeat
                                RepeatMode.OFF -> Icons.Default.Repeat
                            }
                            Icon(
                                repeatIcon,
                                contentDescription = "Repeat",
                                tint = if (repeatMode != RepeatMode.OFF) Accent else TextSecondary.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )

                    Spacer(Modifier.width(12.dp))

                    // Volume control with mute toggle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val volumeIcon = when {
                            isMuted -> Icons.Default.VolumeOff
                            volume <= 0 -> Icons.Default.VolumeOff
                            volume < 50 -> Icons.Default.VolumeDown
                            else -> Icons.Default.VolumeUp
                        }
                        val volumeTint = if (isMuted || volume <= 0) TextSecondary.copy(alpha = 0.4f) else TextSecondary
                        IconButton(
                            onClick = {
                                if (isMuted) {
                                    player.setVolume(previousVolume.value)
                                    isMuted = false
                                } else {
                                    previousVolume.value = volume
                                    player.setVolume(0)
                                    isMuted = true
                                }
                            },
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(
                                volumeIcon,
                                contentDescription = if (isMuted) "Unmute" else "Mute",
                                tint = volumeTint,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = if (isMuted) 0f else volume.toFloat(),
                                onValueChange = {
                                    val newVol = it.toInt()
                                    player.setVolume(newVol)
                                    if (newVol > 0 && isMuted) {
                                        isMuted = false
                                    }
                                },
                                valueRange = 0f..100f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Accent,
                                    activeTrackColor = Accent,
                                    inactiveTrackColor = PsInset
                                ),
                                modifier = Modifier.width(80.dp)
                            )
                            Text(
                                text = if (isMuted || volume <= 0) "0%" else "${volume}%",
                                color = TextSecondary.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontFamily = FontMono,
                                modifier = Modifier.width(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(seconds: Long): String {
    if (seconds <= 0L) return "0:00"
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun PsToggle(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    var shimmerAlpha by remember { mutableStateOf(0f) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Enter ||
                            event.type == androidx.compose.ui.input.pointer.PointerEventType.Move
                        ) {
                            shimmerAlpha = 1f
                        } else if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Exit) {
                            shimmerAlpha = 0f
                        }
                    }
                }
            }
            .clickable(onClick = onClick, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null)
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .drawBehind {
                // Shimmer underline on hover/active
                if (shimmerAlpha > 0f || active) {
                    drawLine(
                        color = if (active) Accent.copy(alpha = 0.6f) else Accent.copy(alpha = shimmerAlpha * 0.5f),
                        start = Offset(0f, size.height - 1f),
                        end = Offset(size.width, size.height - 1f),
                        strokeWidth = 1f
                    )
                }
            }
    ) {
        // Icon with corner reticle brackets
        Box(
            modifier = Modifier
                .size(44.dp)
                .drawBehind {
                    // Corner reticle top-left
                    drawLine(
                        color = if (active) Accent else TextSecondary.copy(alpha = 0.3f),
                        start = Offset(2f, 10f),
                        end = Offset(2f, 2f),
                        strokeWidth = 1f
                    )
                    drawLine(
                        color = if (active) Accent else TextSecondary.copy(alpha = 0.3f),
                        start = Offset(2f, 2f),
                        end = Offset(10f, 2f),
                        strokeWidth = 1f
                    )
                    // Corner reticle bottom-right
                    drawLine(
                        color = if (active) Accent else TextSecondary.copy(alpha = 0.3f),
                        start = Offset(size.width - 2f, size.height - 10f),
                        end = Offset(size.width - 2f, size.height - 2f),
                        strokeWidth = 1f
                    )
                    drawLine(
                        color = if (active) Accent else TextSecondary.copy(alpha = 0.3f),
                        start = Offset(size.width - 2f, size.height - 2f),
                        end = Offset(size.width - 10f, size.height - 2f),
                        strokeWidth = 1f
                    )
                }
        ) {
            icon()
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = if (active) Accent else TextSecondary.copy(alpha = 0.4f),
            fontFamily = FontMono,
            fontSize = 9.sp,
            letterSpacing = 1.4.sp,
        )
    }
}
