package com.wren.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import download.Destination
import download.FileDestination
import download.Output
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Downloads land in the public "Downloads" folder (`/storage/emulated/0/Download`) via
 * MediaStore on API 29+ — visible to any file manager or the Downloads app, and writable
 * without storage permission for files the app itself creates. On older versions falls
 * back to the app's external music directory (no volume → internal storage).
 */
fun downloadsDestination(context: Context): Destination =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreDestination(context)
    else FileDestination(
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music")
    )

@RequiresApi(Build.VERSION_CODES.Q)
private class MediaStoreDestination(private val context: Context) : Destination {
    override fun open(fileName: String): Output {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime(fileName))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("could not create download entry for $fileName")
        val stream = resolver.openOutputStream(uri)
            ?: run {
                resolver.delete(uri, null, null)
                throw IOException("could not open output stream for $fileName")
            }
        return object : Output {
            override val stream: OutputStream = stream
            override val path: String = fileName
            override fun commit() {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
            }
            override fun abort() {
                runCatching { stream.close() }
                resolver.delete(uri, null, null)
            }
        }
    }

    private fun mime(fileName: String): String = when (fileName.substringAfterLast('.', "")) {
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "weba", "webm" -> "audio/webm"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}
