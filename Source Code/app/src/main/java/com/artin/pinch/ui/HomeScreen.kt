package com.artin.pinch.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.artin.pinch.data.Prefs
import com.artin.pinch.engine.Job
import com.artin.pinch.engine.Phase
import com.artin.pinch.engine.Planner
import com.artin.pinch.engine.Quality
import com.artin.pinch.engine.St
import com.artin.pinch.engine.Work
import kotlin.math.roundToInt

private enum class View { Empty, Pick, Run, Done }

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) Work.addUris(ctx, uris)
    }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Work.start(ctx) }
    val start = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else Work.start(ctx)
    }
    val view = when {
        Work.phase == Phase.Run -> View.Run
        Work.phase == Phase.Done -> View.Done
        Work.jobs.isEmpty() -> View.Empty
        else -> View.Pick
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Header {
            if (view == View.Pick) IconBtn(Ic.close, { Work.reset() })
        }
        AnimatedContent(
            view,
            transitionSpec = { (fadeIn(tween(260)) + scaleIn(tween(260), 0.96f)) togetherWith fadeOut(tween(150)) },
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier.weight(1f),
            label = "home",
        ) { v ->
            when (v) {
                View.Empty -> EmptyState(onChoose = { Nav.route = Route.Gallery }, onFiles = { files.launch(arrayOf("image/*", "video/*")) })
                View.Pick -> PickState(start)
                View.Run -> RunState()
                View.Done -> DoneState()
            }
        }
        Spacer(Modifier.height(LocalBarSpace.current))
    }
}

@Composable
private fun EmptyState(onChoose: () -> Unit, onFiles: () -> Unit) {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ChooseOrb(onChoose)
        Spacer(Modifier.height(22.dp))
        Txt(s.choose, t.title, p.text)
        Spacer(Modifier.height(14.dp))
        PillButton(s.files, onFiles, primary = false, icon = Ic.folder, height = 42.dp)
    }
}

/**
 * The big "choose" button is a drop of liquid glass: a lens ring that bends the scene behind it,
 * around a glossy blue core with a specular highlight and a cyan caustic at the bottom. It breathes
 * slowly; touching it swells it and lights it from the finger.
 */
@Composable
private fun ChooseOrb(onClick: () -> Unit) {
    val p = LocalPal.current
    val inf = rememberInfiniteTransition(label = "orb")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "spin")
    val breathe by inf.animateFloat(0.97f, 1.03f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "breathe")
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = breathe; scaleY = breathe }) {
            drawCircle(
                Brush.radialGradient(listOf(p.accent2.copy(alpha = if (p.dark) 0.4f else 0.3f), Color.Transparent), center, size.minDimension / 2),
            )
        }
        Box(
            Modifier.size(164.dp).press(scale = 0.9f, radius = 82.dp, onClick = onClick)
                .glass(82.dp, p.glass.copy(alpha = if (p.dark) 0.22f else 0.28f), depth = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().rotate(spin)) {
                drawCircle(
                    Brush.sweepGradient(listOf(Color.Transparent, p.accent3.copy(alpha = 0.7f), Color.Transparent, p.accent.copy(alpha = 0.6f), Color.Transparent)),
                    style = Stroke(2.dp.toPx()),
                    radius = size.minDimension / 2 - 2.dp.toPx(),
                )
            }
            Box(
                Modifier.size(124.dp).clip(CircleShape).drawBehind {
                    val w = size.width
                    drawCircle(Brush.linearGradient(listOf(p.accent, p.accent2, p.accent3), Offset(0f, 0f), Offset(w, w)))
                    drawCircle(Brush.radialGradient(listOf(p.accent3.copy(alpha = 0.75f), Color.Transparent), Offset(w * 0.62f, w * 0.92f), w * 0.5f))
                    drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color(0x33081A4A)), center, w / 2))
                    drawOval(
                        Brush.radialGradient(listOf(Color(0xCCF6FAFF), Color(0x00F6FAFF)), Offset(w * 0.38f, w * 0.2f), w * 0.32f),
                        Offset(w * 0.14f, w * 0.06f), Size(w * 0.5f, w * 0.3f),
                    )
                    glassRim(false, w / 2, strong = true)
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Ic.plus, p.onAccent, size = 44.dp)
            }
        }
    }
}

/**
 * Two ways to aim, one dial. Quality mode picks how good the result must look and lets each file
 * find its own size; size mode picks how much to remove and shows the quality that will likely
 * cost. Each mode's centre shows its own aim large and the other mode's outcome in the small pill,
 * so the two read as the same trade seen from either end.
 */
@Composable
private fun PickState(start: () -> Unit) {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    val ctx = LocalContext.current
    val byQuality = Prefs.mode == Prefs.MODE_QUALITY
    // Each file is measured in the background; the numbers turn from guesses into measurements.
    // Measuring doesn't depend on the level, so turning the dial never restarts it.
    LaunchedEffect(Prefs.mode, Prefs.codec, Prefs.photo, Work.jobs.size) { Work.measureAll(ctx) }
    val est by remember {
        derivedStateOf {
            val hevc = Prefs.codec == Prefs.CODEC_HEVC
            if (Prefs.mode == Prefs.MODE_QUALITY) {
                val target = Quality.TARGETS[Prefs.level]
                Work.jobs.sumOf { Work.forecastOf(it.m)?.bytes(target, it.m.size) ?: Planner.estimateQuality(it.m, target, hevc) }
            } else Work.jobs.sumOf { Planner.estimate(it.m, Prefs.strength, hevc) }
        }
    }
    val measured by remember { derivedStateOf { Work.jobs.count { Work.forecastOf(it.m) != null } } }
    val total = Work.totalIn
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        ThumbStrip(removable = true)
        Spacer(Modifier.height(6.dp))
        Segmented(s.modes, Prefs.mode, { Prefs.mode = it }, Modifier.width(200.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val d = min(maxWidth * 0.8f, maxHeight)
            StrengthDial(
                value = if (byQuality) Prefs.level.toFloat() else Prefs.strength,
                min = if (byQuality) 0f else Prefs.MIN_STRENGTH,
                max = if (byQuality) 3f else Prefs.MAX_STRENGTH,
                marks = if (byQuality) LEVEL_MARKS else PRESETS,
                onChange = { if (byQuality) Prefs.level = it.roundToInt() else Prefs.strength = it },
                onEnd = { if (!byQuality) Prefs.saveStrength() },
                modifier = Modifier.size(d),
                step = if (byQuality) 1f else 0.01f,
            ) {
                AnimatedContent(
                    byQuality,
                    transitionSpec = { (fadeIn(tween(220)) + scaleIn(tween(260), 0.9f)) togetherWith fadeOut(tween(120)) },
                    label = "aim",
                ) { q ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (q) {
                            AnimatedContent(
                                Prefs.level,
                                transitionSpec = { (fadeIn(tween(200)) + scaleIn(tween(240), 0.92f)) togetherWith fadeOut(tween(100)) },
                                label = "level",
                            ) { lv ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Txt(s.levels[lv], t.big.copy(fontSize = t.big.fontSize * 1.18f), p.text, brush = p.brush)
                                    Txt(s.scoreAtLeast(Quality.TARGETS[lv]), t.small, p.text2)
                                }
                            }
                        } else {
                            Txt(s.pct(Prefs.strength), t.hero, p.text, brush = p.brush)
                            Txt(s.smaller, t.small, p.text2)
                        }
                        Spacer(Modifier.height(8.dp))
                        // The other end of the trade: size for a quality aim, quality for a size aim.
                        val band = Planner.qualityLevel(Prefs.strength)
                        val color = if (q) p.accent else p.scoreColor(BAND_SCORE[band])
                        val n = Work.jobs.size
                        Box(
                            Modifier.glass(50.dp, color.copy(alpha = if (p.dark) 0.2f else 0.1f), depth = 4.dp)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            if (q && measured < n) MeasuringText(s.measuring(measured + 1, n), color)
                            else Txt(
                                if (q) s.drop(if (total > 0) 1f - est.toFloat() / total else 0f) else "≈ " + s.bands[band],
                                t.tiny, color,
                            )
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(s.size(total), t.body, p.text2)
            Spacer(Modifier.width(8.dp))
            Icon(Ic.forward, p.text2, size = 16.dp, mirror = true)
            Spacer(Modifier.width(8.dp))
            // In quality mode the total is only shown once every file is measured; a guess misleads.
            Txt(if (byQuality && measured < Work.jobs.size) "≈ …" else "≈ " + s.size(est), t.title, p.text)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (byQuality) {
                for (lv in 0..3) {
                    val sel = Prefs.level == lv
                    Box(
                        Modifier.press(radius = 18.dp) { Prefs.level = lv }
                            .then(if (sel) Modifier.glossy() else Modifier.glass(18.dp, depth = 6.dp))
                            .padding(horizontal = 13.dp, vertical = 8.dp),
                    ) { Txt(s.levels[lv], t.small, if (sel) p.onAccent else p.text) }
                }
            } else {
                for (v in PRESETS) {
                    val sel = Prefs.strength == v
                    Box(
                        Modifier.press(radius = 18.dp) { Prefs.strength = v; Prefs.saveStrength() }
                            .then(if (sel) Modifier.glossy() else Modifier.glass(18.dp, depth = 6.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Txt(if (v == FIFTH) s.fifthSize else s.pct(v), t.small, if (sel) p.onAccent else p.text) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        PillButton(
            s.compress(Work.jobs.size), start, Modifier.fillMaxWidth().padding(horizontal = 16.dp), icon = Ic.pinch,
        )
        Spacer(Modifier.height(4.dp))
    }
}

private val PRESETS = listOf(0.5f, 0.75f, 0.8f, 0.9f, 0.98f)
/** The preset named by what is left: a fifth of the size. */
private const val FIFTH = 0.8f
private val LEVEL_MARKS = listOf(0f, 1f, 2f, 3f)
/** A score inside each result band, for colouring the size mode's quality guess. */
private val BAND_SCORE = floatArrayOf(98f, 94f, 89f, 80f, 60f)

/** Picked files; while running each tile shows its own state. */
@Composable
private fun ThumbStrip(removable: Boolean) {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    LazyRow(
        Modifier.fillMaxWidth().height(84.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(Work.jobs, key = { it.m.key }) { job ->
            Box(Modifier.size(72.dp).animateItem()) {
                Thumb(job.m.storeUri ?: job.m.uri, job.m.video, Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)))
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(26.dp)
                        .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99140F2A)))),
                )
                Txt(s.size(job.m.size), t.tiny, ON_IMAGE, Modifier.align(Alignment.BottomStart).padding(6.dp))
                val fc = if (removable && Prefs.mode == Prefs.MODE_QUALITY) Work.forecastOf(job.m) else null
                androidx.compose.animation.AnimatedVisibility(
                    fc != null,
                    Modifier.align(Alignment.TopStart).padding(4.dp),
                    enter = fadeIn(tween(220)) + scaleIn(tween(260), 0.6f),
                    exit = fadeOut(tween(120)),
                ) {
                    val f = fc ?: return@AnimatedVisibility
                    val target = Quality.TARGETS[Prefs.level]
                    val keep = f.keep(target)
                    // "Already optimal" is a check mark, not a word: a word ran under the remove button.
                    if (keep) Box(
                        Modifier.size(20.dp).clip(CircleShape).background(Color(0xCC140F2A)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Ic.check, ON_IMAGE, size = 12.dp) }
                    else Box(
                        Modifier.clip(RoundedCornerShape(50)).background(p.good.copy(alpha = 0.92f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) { Txt(s.pct(1f - f.bytes(target, job.m.size).toFloat() / job.m.size), t.tiny, ON_IMAGE) }
                }
                when {
                    removable -> Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape)
                            .background(Color(0x99140F2A)).press { Work.remove(job) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Ic.close, ON_IMAGE, size = 14.dp) }
                    job.st == St.Done -> Badge(Ic.check, p.good, Modifier.align(Alignment.TopEnd))
                    job.st == St.Fail -> Badge(Ic.close, p.bad, Modifier.align(Alignment.TopEnd))
                    job.st == St.Skip -> Badge(Ic.check, p.text2, Modifier.align(Alignment.TopEnd))
                    job.st == St.Run -> ProgressRing(job.progress, Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp), 3f)
                }
            }
        }
        if (removable) item(key = "add") {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).border(1.5.dp, p.line, RoundedCornerShape(16.dp))
                    .press { Nav.route = Route.Gallery },
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.plus, p.text2, size = 24.dp) }
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier) {
    Box(modifier.padding(4.dp).size(22.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, ON_IMAGE, size = 13.dp)
    }
}

private val ON_IMAGE = Color(0xFFF7F4FF)

@Composable
private fun RunState() {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    val overall by animateFloatAsState(Work.overall, tween(400), label = "overall")
    val saved by animateFloatAsState(Work.saved.toFloat(), tween(900), label = "saved")
    val cur = Work.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        ThumbStrip(removable = false)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val d = min(maxWidth * 0.74f, maxHeight)
            ProgressRing(overall, Modifier.size(d), 16f) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (cur != null) {
                        Thumb(
                            cur.m.storeUri ?: cur.m.uri, cur.m.video,
                            Modifier.size(d * 0.36f).clip(CircleShape).graphicsLayer {
                                // The current file gently squeezes while it is being worked on.
                                val f = 1f - 0.12f * cur.progress
                                scaleY = f
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Txt(s.pct(overall), t.big, p.text, brush = p.brush)
                }
            }
        }
        if (cur != null) {
            val i = Work.jobs.indexOf(cur) + 1
            Txt(s.num(i) + " / " + s.num(Work.jobs.size) + " · " + cur.m.name, t.small, p.text2, Modifier.padding(horizontal = 32.dp))
            Spacer(Modifier.height(4.dp))
            StageLine(cur.stage)
        }
        Spacer(Modifier.height(8.dp))
        Txt(if (saved >= 1f) s.savedSoFar(s.size(saved.toLong())) else " ", t.title, p.text, brush = p.brush)
        Spacer(Modifier.height(16.dp))
        PillButton(s.cancel, { Work.cancel() }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), primary = false, icon = Ic.close)
        Spacer(Modifier.height(4.dp))
    }
}

/** Text with a pulsing dot, for work going on in the background. */
@Composable
private fun MeasuringText(text: String, color: Color) {
    val t = LocalType.current
    val inf = rememberInfiniteTransition(label = "measure")
    val pulse by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(6.dp)) { drawCircle(color.copy(alpha = pulse)) }
        Spacer(Modifier.width(5.dp))
        Txt(text, t.tiny, color)
    }
}

/** The current step, with a dot that pulses while it works. */
@Composable
private fun StageLine(stage: Int) {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    val inf = rememberInfiniteTransition(label = "stage")
    val pulse by inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    AnimatedContent(
        stage,
        transitionSpec = { (fadeIn(tween(220)) + scaleIn(tween(220), 0.9f)) togetherWith fadeOut(tween(120)) },
        label = "stageText",
    ) { st ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(7.dp)) { drawCircle(p.accent2.copy(alpha = pulse)) }
            Spacer(Modifier.width(6.dp))
            Txt(s.stages[st.coerceIn(0, 2)], t.tiny, p.accent)
        }
    }
}

@Composable
private fun DoneState() {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    val ctx = LocalContext.current
    val finished = Work.jobs.filter { it.st != St.Wait }
    val inBytes = Work.finishedIn
    val outBytes = Work.finishedOut
    val ratio = if (inBytes > 0) 1f - outBytes.toFloat() / inBytes else 0f
    val ring by animateFloatAsState(ratio, tween(1100), label = "ring")
    val scored = finished.filter { it.st == St.Done && it.score >= 0f }
    // Size-weighted, so a big video counts more than a small photo.
    val avg = if (scored.isEmpty()) -1f
    else (scored.sumOf { it.score.toDouble() * it.m.size } / scored.sumOf { it.m.size.toDouble() }).toFloat()
    val trashable = finished.filter { it.st == St.Done && it.m.storeUri != null && !it.trashed }
    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) trashable.forEach { it.trashed = true }
    }
    Column(Modifier.fillMaxSize()) {
        // The summary card scrolls with the results, so a short (landscape) screen still shows the list.
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 12.dp),
        ) {
            item(key = "summary") {
                Row(
                    Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp).fillMaxWidth().card(p.surface).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProgressRing(ring, Modifier.size(84.dp), 9f) { Txt(s.pct(ratio), t.bodyB, p.text) }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(s.saved, t.small, p.text2)
                        Txt(s.size(inBytes - outBytes), t.big, p.text, brush = p.brush)
                        Txt(s.size(inBytes) + s.arrow + s.size(outBytes), t.small, p.text2)
                        if (avg >= 0f) QualityTag(avg, Modifier.padding(top = 4.dp), words = true)
                    }
                }
            }
            items(finished, key = { it.m.key }) { ResultRow(it, it !== finished.last()) }
        }
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val outs = finished.mapNotNull { it.outUri }
            Box(
                Modifier.size(54.dp).press(outs.isNotEmpty(), 0.9f, 27.dp) {
                    val mime = when {
                        finished.all { it.m.video } -> "video/*"
                        finished.none { it.m.video } -> "image/*"
                        else -> "*/*"
                    }
                    shareUris(ctx, outs, mime)
                }.glass(27.dp, depth = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.share, p.text) }
            if (trashable.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                PillButton(s.trashOriginals, {
                    val pi = MediaStore.createTrashRequest(ctx.contentResolver, trashable.map { it.m.storeUri!! }, true)
                    trash.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                }, Modifier.weight(1f), primary = false, icon = Ic.trash)
            } else Spacer(Modifier.weight(1f))
            PillButton(s.newBatch, { Work.reset() }, icon = Ic.plus)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ResultRow(job: Job, divider: Boolean) {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    val done = job.st == St.Done
    ListRow(divider, done, { Nav.route = Route.Compare(job) }) {
        Thumb(job.outUri ?: job.m.storeUri ?: job.m.uri, job.m.video, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(job.m.name, t.body, p.text)
            val line = when (job.st) {
                St.Done -> s.size(job.m.size) + s.arrow + s.size(job.outSize) + if (job.trashed) " · " + s.trashed else ""
                St.Skip -> s.keptOriginal
                St.Fail -> s.failed
                else -> s.waiting
            }
            Txt(line, t.small, if (job.st == St.Fail) p.bad else p.text2)
        }
        if (done) {
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(p.good.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
                ) { Txt(s.drop(1f - job.outSize.toFloat() / job.m.size), t.tiny, p.good) }
                if (job.score >= 0f) QualityTag(job.score, Modifier.padding(top = 4.dp))
            }
        }
    }
}
