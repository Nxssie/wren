package com.wren.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Radio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import download.DownloadManager
import models.QueueItem
import models.SearchResult

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, corner: Int = 2) {
    val shape = RoundedCornerShape(corner.dp)
    if (url.isNullOrBlank()) {
        Box(modifier.clip(shape).background(PsInset), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextSecondary)
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
    }
}

/** One search/discover result row: thumbnail, title, subtitle and an optional action. */
@Composable
fun TrackRow(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    trailingLabel: String? = null,
    highlight: Boolean = false,
    onClick: () -> Unit,
    onAction: (() -> Unit)? = null,
    actionIcon: ImageVector = Icons.Default.Radio,
    actionDescription: String = "Start radio",
    onDownload: (() -> Unit)? = null,
    downloadState: DownloadManager.State? = null,
    liked: Boolean? = null,
    onLike: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artworkUrl, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (highlight) PsIrisCyan else TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingLabel != null) {
            Text(trailingLabel, color = PsSteel400, fontFamily = FontMono, fontSize = 12.sp)
        }
        if (onLike != null) LikeButton(liked == true, onLike)
        if (onDownload != null) DownloadButton(downloadState, onDownload)
        if (onAction != null) {
            IconButton(onClick = onAction) {
                Icon(actionIcon, contentDescription = actionDescription, tint = TextSecondary)
            }
        }
    }
}

/** Heart toggle for SoundCloud likes; filled and cyan when the track is liked. */
@Composable
fun LikeButton(liked: Boolean, onClick: () -> Unit, tint: androidx.compose.ui.graphics.Color = TextSecondary) {
    IconButton(onClick = onClick) {
        Icon(
            if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (liked) "Remove from liked" else "Add to liked",
            tint = if (liked) PsIrisCyan else tint,
        )
    }
}

/** Download action that reflects the [DownloadManager] state of the track it stands for. */
@Composable
fun DownloadButton(
    state: DownloadManager.State?,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = TextSecondary,
) {
    IconButton(onClick = onClick, enabled = state !is DownloadManager.State.Downloading) {
        when (state) {
            is DownloadManager.State.Downloading -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = PsIrisCyan,
                strokeWidth = 1.5.dp,
            )
            is DownloadManager.State.Done -> Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Downloaded",
                tint = PsIrisCyan,
            )
            is DownloadManager.State.Failed -> Icon(
                Icons.Default.ErrorOutline,
                contentDescription = "Download failed, tap to retry",
                tint = PsSignalDanger,
            )
            null -> Icon(
                Icons.Default.Download,
                contentDescription = "Download",
                tint = tint,
            )
        }
    }
}

fun SearchResult.subtitleText(): String =
    listOf(artist, duration).filter { it.isNotBlank() }.joinToString(" · ")

fun QueueItem.subtitleText(): String =
    listOf(artist, source.name.lowercase()).filter { it.isNotBlank() }.joinToString(" · ")
