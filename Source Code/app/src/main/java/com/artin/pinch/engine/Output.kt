package com.artin.pinch.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/** Results go to Pictures/Squish and Movies/Squish; the originals are never touched. */
object Output {
    fun create(ctx: Context, video: Boolean, srcName: String, ext: String, mime: String, dateTaken: Long): Uri {
        val base = srcName.substringBeforeLast('.').ifBlank { "pinch" }.take(80)
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$base-squish.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Squish",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (dateTaken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
        }
        val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return ctx.contentResolver.insert(coll, cv) ?: error("Cannot create the output file")
    }

    fun publish(ctx: Context, uri: Uri) {
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun discard(ctx: Context, uri: Uri) {
        runCatching { ctx.contentResolver.delete(uri, null, null) }
    }
}

class Cancelled : RuntimeException("cancelled")
