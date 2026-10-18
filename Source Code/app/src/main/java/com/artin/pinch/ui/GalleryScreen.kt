package com.artin.pinch.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.artin.pinch.engine.Media
import com.artin.pinch.engine.Work
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val video: Boolean,
    val durationMs: Long,
    val w: Int,
    val h: Int,
    val date: Long,
    val taken: Long,
) {
    /** One Media per item, so measuring started on tick carries over when it is added. */
    val media: Media by lazy { Media(uri, name, mime, size, video, w, h, durationMs, taken, uri) }
}

private fun mediaPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 34) {
    arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
} else if (Build.VERSION.SDK_INT >= 33) {
    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)
} else {
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
}

private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/** 0 none, 1 only the photos the user picked (Android 14), 2 full. */
private fun access(ctx: Context): Int = when {
    Build.VERSION.SDK_INT >= 33 &&
        (granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) || granted(ctx, Manifest.permission.READ_MEDIA_VIDEO)) -> 2
    Build.VERSION.SDK_INT < 33 && granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) -> 2
    Build.VERSION.SDK_INT >= 34 && granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> 1
    else -> 0
}

private fun queryGallery(ctx: Context): List<GItem> {
    val out = ArrayList<GItem>()
    val cols = arrayOf(
        MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_TAKEN,
    )
    val sel = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
        "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND " +
        "${MediaStore.Files.FileColumns.SIZE}>0 AND ${MediaStore.Files.FileColumns.MIME_TYPE}!='image/gif' AND " +
        "(${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL OR ${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE '%/Pinch/%' AND ${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE '%/Squish/%')"
    ctx.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, sel, null, null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val video = c.getInt(4) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            out += GItem(
                id, ContentUris.withAppendedId(base, id), c.getString(1) ?: "", c.getString(2) ?: "",
                c.getLong(3), video, c.getLong(5), c.getInt(6), c.getInt(7), c.getLong(8), c.getLong(9),
            )
        }
    }
    return out
}

@Composable
fun GalleryScreen() {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    val ctx = LocalContext.current
    var acc by remember { mutableIntStateOf(access(ctx)) }
    var items by remember { mutableStateOf<List<GItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        acc = access(ctx); reload++
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { Work.addUris(ctx, uris); Nav.route = null }
    }
    DisposableEffect(Unit) { onDispose { Work.forgetUnpicked() } }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var largest by rememberSaveable { mutableStateOf(true) }
    val sel = remember { mutableStateMapOf<Long, GItem>() }
    LaunchedEffect(acc, reload) {
        if (acc > 0) items = withContext(Dispatchers.IO) { runCatching { queryGallery(ctx) }.getOrDefault(emptyList()) }
    }
    val shown = remember(items, filter, largest) {
        items?.filter { filter == 0 || (filter == 2) == it.video }
            ?.let { l -> if (largest) l.sortedByDescending { it.size } else l.sortedByDescending { maxOf(it.taken / 1000, it.date) } }
    }

    Box(Modifier.fillMaxSize().backdrop()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.back, { Nav.route = null }, mirror = true)
                Spacer(Modifier.width(10.dp))
                Txt(s.gallery, t.title, p.text, Modifier.weight(1f))
                if (acc == 1) {
                    PillButton(s.more, { ask.launch(mediaPermissions()) }, primary = false, height = 40.dp)
                    Spacer(Modifier.width(8.dp))
                }
                if (!shown.isNullOrEmpty()) {
                    val allOn = shown.all { sel.containsKey(it.id) }
                    IconBtn(Ic.check, {
                        if (allOn) shown.forEach { sel.remove(it.id); Work.forget(it.media) }
                        else shown.forEach { sel[it.id] = it; Work.measure(ctx, it.media) }
                    }, tint = if (allOn) p.accent2 else null)
                    Spacer(Modifier.width(8.dp))
                }
                IconBtn(Ic.folder, { files.launch(arrayOf("image/*", "video/*")) })
            }
            if (acc > 0) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Segmented(listOf(s.all, s.photos, s.videos), filter, { filter = it }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.height(38.dp).press(radius = 19.dp) { largest = !largest }.glass(19.dp, depth = 6.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Ic.sort, p.text, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Txt(if (largest) s.largest else s.newest, t.small, p.text)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    acc == 0 -> Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.size(84.dp).glass(42.dp, depth = 14.dp), contentAlignment = Alignment.Center) {
                            Icon(Ic.photo, p.accent, size = 38.dp)
                        }
                        Spacer(Modifier.height(18.dp))
                        PillButton(s.allowAccess, { ask.launch(mediaPermissions()) })
                        Spacer(Modifier.height(10.dp))
                        PillButton(s.files, { files.launch(arrayOf("image/*", "video/*")) }, primary = false, icon = Ic.folder, height = 44.dp)
                    }
                    shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ProgressRing(0.25f, Modifier.size(36.dp), 4f)
                    }
                    shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Txt(s.emptyGallery, t.body, p.text2)
                    }
                    // A new sort or filter starts from the top: a kept grid state would follow the first visible
                    // item (by key) to its new place, e.g. the largest file to the end of the "newest" order.
                    else -> key(filter, largest) {
                        LazyVerticalGrid(
                            GridCells.Adaptive(104.dp),
                            Modifier.fillMaxSize(),
                            state = rememberLazyGridState(),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 120.dp),
                        ) {
                            items(shown, key = { it.id }) { g ->
                                Tile(g, sel.containsKey(g.id)) {
                                    if (sel.remove(g.id) == null) { sel[g.id] = g; Work.measure(ctx, g.media) } else Work.forget(g.media)
                                }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            sel.isNotEmpty(),
            Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            PillButton(
                s.addN(sel.size, s.size(sel.values.sumOf { it.size })),
                {
                    Work.add(ctx, sel.values.sortedByDescending { it.size }.map { it.media })
                    Nav.route = null
                },
                Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth(),
                icon = Ic.check,
            )
        }
    }
}

@Composable
private fun Tile(g: GItem, selected: Boolean, onToggle: () -> Unit) {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    val inset by animateDpAsState(if (selected) 7.dp else 0.dp, label = "inset")
    Box(Modifier.aspectRatio(1f).padding(2.dp).press(scale = 0.96f, onClick = onToggle)) {
        Box(Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(12.dp))) {
            Thumb(g.uri, g.video, Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xA0140F2A)))),
            )
            Txt(s.size(g.size), t.tiny, ON_IMG, Modifier.align(Alignment.BottomStart).padding(6.dp))
            if (g.video) Row(
                Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(50))
                    .background(Color(0x80140F2A)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ic.play, ON_IMG, size = 10.dp)
                if (g.durationMs > 0) {
                    Spacer(Modifier.width(3.dp))
                    Txt(s.duration(g.durationMs), t.tiny, ON_IMG)
                }
            }
        }
        if (selected) Box(Modifier.fillMaxSize().border(2.dp, p.accent2, RoundedCornerShape(14.dp)))
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                .then(
                    if (selected) Modifier.background(p.hBrush)
                    else Modifier.background(Color(0x40140F2A)).border(1.5.dp, ON_IMG, CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Ic.check, ON_IMG, size = 14.dp) }
    }
}

private val ON_IMG = Color(0xFFF7F4FF)
