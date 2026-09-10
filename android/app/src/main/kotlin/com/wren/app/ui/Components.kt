package com.wren.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
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
        if (onAction != null) {
            IconButton(onClick = onAction) {
                Icon(actionIcon, contentDescription = actionDescription, tint = TextSecondary)
            }
        }
    }
}

fun SearchResult.subtitleText(): String =
    listOf(artist, duration).filter { it.isNotBlank() }.joinToString(" · ")

fun QueueItem.subtitleText(): String =
    listOf(artist, source.name.lowercase()).filter { it.isNotBlank() }.joinToString(" · ")
