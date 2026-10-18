package com.artin.pinch.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

fun shareUris(ctx: Context, uris: List<Uri>, mime: String) {
    if (uris.isEmpty()) return
    val i = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    i.type = mime
    i.clipData = ClipData.newRawUri(null, uris[0]).apply { for (u in uris.drop(1)) addItem(ClipData.Item(u)) }
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
}

fun openUri(ctx: Context, uri: Uri, mime: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(i) }
}
