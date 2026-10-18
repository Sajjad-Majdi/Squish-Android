package com.artin.pinch.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.artin.pinch.data.Entry
import com.artin.pinch.data.History
import com.artin.pinch.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { Pick, Run, Done }
enum class St { Wait, Run, Done, Skip, Fail }

@Stable
class Job(val m: Media) {
    var st by mutableStateOf(St.Wait)
    var progress by mutableFloatStateOf(0f)
    var outSize by mutableLongStateOf(0L)
    var outUri by mutableStateOf<Uri?>(null)
    var trashed by mutableStateOf(false)
    /** Measured quality of the result, 0–100; −1 when unknown. */
    var score by mutableFloatStateOf(-1f)
    /** [STAGE_ANALYZE], [STAGE_COMPRESS] or [STAGE_CHECK]. */
    var stage by mutableIntStateOf(STAGE_COMPRESS)
}

/**
 * One measurement of a file that answers for every quality level, so turning the dial never waits.
 * [keep] means the file is already as small as that quality allows.
 */
interface Forecast {
    fun bytes(target: Float, size: Long): Long
    fun keep(target: Float): Boolean
}

const val STAGE_ANALYZE = 0
const val STAGE_COMPRESS = 1
const val STAGE_CHECK = 2

/** The current batch. All state changes happen on the main thread, except per-job progress. */
object Work {
    val jobs = mutableStateListOf<Job>()
    var phase by mutableStateOf(Phase.Pick)
    var current by mutableStateOf<Job?>(null)
    var probing by mutableStateOf(0)

    @Volatile
    var cancelled = false
        private set

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val probeIo = Dispatchers.IO.limitedParallelism(2)
    // Videos measure on the GPU and the media codecs, photos on the CPU, so they run side by side.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val videoIo = Dispatchers.IO.limitedParallelism(1)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val photoIo = Dispatchers.IO.limitedParallelism(1)
    private var videoWorker: kotlinx.coroutines.Job? = null
    private var photoWorker: kotlinx.coroutines.Job? = null
    /** Files waiting to be measured, in the order they were chosen. Main thread only. */
    private val pending = ArrayList<Media>()
    private val probingKeys = HashSet<String>()
    /** The measurement in flight per file, so unticking can stop it. Main thread only. */
    private val running = HashMap<String, kotlinx.coroutines.Job>()
    /** Measured curves by file and encoder settings; one per file answers every quality level. */
    private val forecasts = mutableStateMapOf<String, Forecast>()

    /** The settings a forecast depends on; the quality level is not one of them. */
    private fun forecastKey(m: Media): String = m.key + "|" + Prefs.codec + "/" + Prefs.photo

    fun forecastOf(m: Media): Forecast? = forecasts[forecastKey(m)]

    /**
     * Starts measuring a file in the background: called as soon as it is ticked in the gallery,
     * so most of the work is done by the time the Pick screen shows.
     */
    fun measure(ctx: Context, m: Media) {
        if (phase == Phase.Run || Prefs.mode != Prefs.MODE_QUALITY) return
        if (forecasts.containsKey(forecastKey(m)) || pending.any { it.key == m.key }) return
        // Nothing is read yet: a video's details are probed only when its turn comes, so an
        // accidental "select all" costs nothing once it is undone.
        pending += m
        pump(ctx.applicationContext)
    }

    /** A file was unticked before it was added: drop it, and stop it if it is being measured. */
    fun forget(m: Media) {
        if (jobs.any { it.m.key == m.key }) return
        pending.removeAll { it.key == m.key }
        running[m.key]?.cancel()
    }

    /** Leaving the gallery: forget whatever was ticked but not added. */
    fun forgetUnpicked() {
        val keep = jobs.mapTo(HashSet()) { it.m.key }
        pending.removeAll { it.key !in keep }
        running.filterKeys { it !in keep }.values.forEach { it.cancel() }
    }

    /** Queues every picked file (after a settings change, or for files shared into the app). */
    fun measureAll(ctx: Context) {
        for (j in jobs) measure(ctx, j.m)
    }

    private fun pump(ctx: Context) {
        if (videoWorker?.isActive != true && pending.any { it.video }) videoWorker = bg.launch { drain(ctx, true) }
        if (photoWorker?.isActive != true && pending.any { !it.video }) photoWorker = bg.launch { drain(ctx, false) }
    }

    private suspend fun drain(ctx: Context, video: Boolean) {
        while (true) {
            val m = pending.firstOrNull { it.video == video } ?: return
            pending.remove(m)
            val key = forecastKey(m)
            if (forecasts.containsKey(key)) continue
            val hevc = Prefs.codec == Prefs.CODEC_HEVC
            val photo = Prefs.photo
            val t0 = android.os.SystemClock.uptimeMillis()
            val task = bg.async(if (video) videoIo else photoIo) {
                val scope = this
                runCatching {
                    if (video) {
                        val info = m.info ?: Probe.video(ctx, m).also { withContext(Dispatchers.Main) { m.info = it } }
                        VideoQuality.curve(ctx, m, info, hevc, { !scope.isActive }) {}
                    } else ImageCompressor.curve(ctx, m, photo) { !scope.isActive }
                }.onFailure { if (scope.isActive) Log.w("Pinch", "forecast ${m.name}", it) }.getOrNull()
            }
            running[m.key] = task
            val f = try {
                task.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                null // only this file was cancelled (unticked); carry on with the next one
            } finally {
                running.remove(m.key)
            }
            if (f != null) {
                forecasts[key] = f
                Log.i("Pinch", "measured ${m.name} in ${android.os.SystemClock.uptimeMillis() - t0} ms")
            }
        }
    }

    private fun stopMeasuring() {
        videoWorker?.cancel(); photoWorker?.cancel()
        running.values.forEach { it.cancel() }
    }

    fun add(ctx: Context, list: List<Media>) {
        if (phase == Phase.Run) return
        if (phase == Phase.Done) reset()
        val have = jobs.mapTo(HashSet()) { it.m.key }
        for (m in list) if (have.add(m.key)) {
            jobs += Job(m)
            if (m.video && m.info == null) probe(ctx.applicationContext, m)
            measure(ctx, m)
        }
    }

    /** Shared or picked files: read their details off the main thread first. */
    fun addUris(ctx: Context, uris: List<Uri>) {
        val app = ctx.applicationContext
        probing++
        bg.launch {
            val ms = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { Probe.fromUri(app, it) }.getOrNull() } }
            probing--
            add(app, ms)
        }
    }

    private fun probe(ctx: Context, m: Media) {
        if (!probingKeys.add(m.key)) return
        bg.launch {
            val r = withContext(probeIo) { runCatching { Probe.video(ctx, m) } }
            r.onSuccess { m.info = it }.onFailure { m.probeFailed = true; Log.w("Pinch", "probe ${m.name}", it) }
            probingKeys.remove(m.key)
        }
    }

    fun remove(job: Job) { if (phase == Phase.Pick) jobs.remove(job) }

    fun reset() {
        if (phase == Phase.Run) return
        jobs.clear(); current = null; phase = Phase.Pick
    }

    fun start(ctx: Context) {
        if (jobs.isEmpty() || phase == Phase.Run) return
        stopMeasuring()
        cancelled = false
        for (j in jobs) if (j.st != St.Done) { j.st = St.Wait; j.progress = 0f }
        phase = Phase.Run
        ctx.startForegroundService(Intent(ctx, CompressService::class.java))
    }

    fun cancel() { cancelled = true }

    val totalIn: Long get() = jobs.sumOf { it.m.size }
    val saved: Long get() = jobs.sumOf { if (it.st == St.Done) it.m.size - it.outSize else 0L }
    val finishedIn: Long get() = jobs.sumOf { if (it.st == St.Done || it.st == St.Skip || it.st == St.Fail) it.m.size else 0L }
    val finishedOut: Long get() = jobs.sumOf { if (it.st == St.Done || it.st == St.Skip || it.st == St.Fail) it.outSize else 0L }

    /** Overall progress weighted by file size. */
    val overall: Float
        get() {
            val total = totalIn.coerceAtLeast(1)
            var done = 0.0
            for (j in jobs) done += j.m.size * when (j.st) {
                St.Done, St.Skip, St.Fail -> 1f
                St.Run -> j.progress
                St.Wait -> 0f
            }
            return (done / total).toFloat().coerceIn(0f, 1f)
        }

    /** Runs every waiting job, one after another. Called by [CompressService] on the main thread. */
    suspend fun runAll(ctx: Context, tick: () -> Unit) {
        val s = Prefs.strength
        val target: Float? = if (Prefs.mode == Prefs.MODE_QUALITY) Quality.TARGETS[Prefs.level] else null
        val hevc = Prefs.codec == Prefs.CODEC_HEVC
        val photo = Prefs.photo
        val keepLoc = Prefs.keepLocation &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        for (job in jobs.toList()) {
            if (cancelled) break
            if (job.st != St.Wait) continue
            current = job
            job.st = St.Run
            job.progress = 0f
            job.score = -1f
            tick()
            val m = job.m
            val fc = if (target != null) forecastOf(m) else null
            val r: Result<Triple<Uri, Long, Float>?> = withContext(Dispatchers.IO) {
                runCatching {
                    if (target != null && fc?.keep(target) == true) null
                    else if (m.video) {
                        val info = m.info ?: Probe.video(ctx, m)
                        // Quality mode: probe first (0–25 %), then encode, then check (last 8 %).
                        var base = 0f
                        val plan = if (target != null && fc is VideoQuality.Curve) fc.planFor(target)
                        else if (target != null) {
                            job.stage = STAGE_ANALYZE
                            val p = runCatching {
                                VideoQuality.curve(ctx, m, info, hevc, { cancelled }) { job.progress = 0.25f * it }.planFor(target)
                            }.getOrElse {
                                if (it is Cancelled) throw it
                                Log.w("Pinch", "probe failed ${m.name}", it)
                                Planner.quality(info, hevc, target)
                            }
                            base = 0.25f
                            p
                        } else Planner.video(info, s, hevc) ?: error("No video encoder")
                        if (plan == null) null
                        else {
                            job.stage = STAGE_COMPRESS
                            val span = 0.92f - base
                            VideoCompressor(ctx, m, info, plan, keepLoc, { cancelled }, { job.progress = base + span * it }) {
                                job.stage = STAGE_CHECK
                                job.progress = 0.93f
                            }.run()?.let { Triple(it.uri, it.size, it.score) }
                        }
                    } else {
                        job.stage = STAGE_COMPRESS
                        ImageCompressor.run(ctx, m, s, target, photo, keepLoc, { cancelled }, { job.progress = it }, if (target != null) (fc as? ImageCompressor.Curve)?.qFor(target) else null)
                            ?.let { Triple(it.uri, it.size, it.score) }
                    }
                }
            }
            val out = r.getOrNull()
            when {
                out != null -> {
                    job.outUri = out.first
                    job.outSize = out.second
                    job.score = out.third
                    job.st = St.Done
                    History.add(Entry(m.name, m.size, out.second, out.first, m.video, System.currentTimeMillis(), out.third))
                }
                r.isSuccess -> { job.outSize = m.size; job.st = St.Skip }
                cancelled -> { job.st = St.Wait; job.progress = 0f }
                else -> {
                    Log.w("Pinch", "failed ${m.name}", r.exceptionOrNull())
                    job.outSize = m.size; job.st = St.Fail
                }
            }
            if (job.st != St.Wait) job.progress = 1f
            tick()
        }
        current = null
        phase = if (jobs.any { it.st != St.Wait }) Phase.Done else Phase.Pick
    }
}
