package com.artin.pinch.ui

import android.app.Activity
import android.graphics.Color as AColor
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.artin.pinch.data.Prefs
import com.artin.pinch.engine.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

sealed interface Route {
    data object Gallery : Route
    class Compare(val job: Job) : Route
}

object Nav {
    var tab by mutableIntStateOf(0)
    var route by mutableStateOf<Route?>(null)
}

/** Height a page must keep clear at its bottom so nothing hides under the floating bar. */
val LocalBarSpace = compositionLocalOf { 0.dp }

@Composable
fun PinchRoot() {
    val dark = when (Prefs.theme) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val activity = LocalContext.current as Activity
    LaunchedEffect(dark) {
        val style = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT)
        else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
        (activity as ComponentActivity).enableEdgeToEdge(style, style)
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = AColor.TRANSPARENT
        activity.window.isNavigationBarContrastEnforced = false
    }
    PinchTheme(dark, Prefs.lang) {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        BackdropHost {
            GlassScaffold(bar = { NavBar() }) {
                AnimatedContent(
                    Nav.tab,
                    transitionSpec = { liquidSlide(if (rtl) -1 else 1) },
                    contentAlignment = Alignment.TopCenter,
                    label = "tabs",
                ) { tab ->
                    Box(Modifier.fillMaxSize().liquidBlur(this)) {
                        when (tab) {
                            0 -> HomeScreen()
                            1 -> HistoryScreen()
                            else -> SettingsScreen()
                        }
                    }
                }
            }
            AnimatedContent(
                Nav.route,
                transitionSpec = {
                    if (targetState != null) {
                        (scaleIn(spring(0.78f, 300f), 0.9f) + fadeIn(tween(200))) togetherWith fadeOut(tween(200))
                    } else {
                        fadeIn(tween(160)) togetherWith (scaleOut(tween(260, easing = FastOutSlowInEasing), 0.92f) + fadeOut(tween(220)))
                    }
                },
                label = "route",
            ) { r ->
                when (r) {
                    null -> Box(Modifier)
                    Route.Gallery -> Box(Modifier.liquidBlur(this)) { GalleryScreen() }
                    is Route.Compare -> Box(Modifier.liquidBlur(this)) { CompareScreen(r.job) }
                }
            }
        }
        BackHandler(Nav.route != null) { Nav.route = null }
        BackHandler(Nav.route == null && Nav.tab != 0) { Nav.tab = 0 }
    }
}

/** Pages flow sideways in the direction of the tab, shrinking a little and melting in. */
private fun AnimatedContentTransitionScope<Int>.liquidSlide(sign: Int): ContentTransform {
    val dir = (if (targetState > initialState) 1 else -1) * sign
    val move = spring<IntOffset>(dampingRatio = 0.86f, stiffness = 170f)
    val grow = spring<Float>(dampingRatio = 0.8f, stiffness = 170f)
    return (slideInHorizontally(move) { it * dir / 3 } + scaleIn(grow, 0.92f) + fadeIn(tween(320, 60))) togetherWith
        (slideOutHorizontally(move) { -it * dir / 3 } + scaleOut(tween(340), 0.92f) + fadeOut(tween(240)))
}

/** Blurs a page while it enters or leaves, so transitions look like liquid, not cuts. */
@Composable
fun Modifier.liquidBlur(scope: AnimatedVisibilityScope): Modifier {
    if (Build.VERSION.SDK_INT < 31) return this
    val r by scope.transition.animateFloat(
        transitionSpec = { tween(360) }, label = "melt",
    ) { if (it == EnterExitState.Visible) 0f else 22f }
    return this.graphicsLayer {
        val px = r * density
        renderEffect = if (px > 0.5f) BlurEffect(px, px, TileMode.Decal) else null
    }
}

/**
 * The soft blue scene behind everything: three wide colour pools. They drift to new places when
 * the tab or screen changes, so the whole app moves like one liquid. Glass reads them through
 * [LocalBackdrop].
 */
@Composable
private fun BackdropHost(content: @Composable () -> Unit) {
    val p = LocalPal.current
    val bg = rememberGraphicsLayer()
    val key = when (Nav.route) {
        null -> Nav.tab
        Route.Gallery -> 3
        is Route.Compare -> 4
    }
    val drift = spring<Offset>(dampingRatio = 0.9f, stiffness = 40f)
    val a by animateOffsetAsState(POOL_A[key], drift, label = "a")
    val b by animateOffsetAsState(POOL_B[key], drift, label = "b")
    val c by animateOffsetAsState(POOL_C[key], drift, label = "c")
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().drawWithContent {
                bg.record { this@drawWithContent.drawContent() }
                drawLayer(bg)
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(p.bg)
                val w = size.width
                val k = if (p.dark) 1.15f else 1f
                pool(Offset(a.x * w, a.y * size.height), w * 0.85f, p.accent.copy(alpha = 0.30f * k))
                pool(Offset(b.x * w, b.y * size.height), w * 0.75f, p.accent3.copy(alpha = 0.26f * k))
                pool(Offset(c.x * w, c.y * size.height), w * 0.8f, p.accent2.copy(alpha = 0.24f * k))
            }
        }
        CompositionLocalProvider(LocalBackdrop provides bg) { content() }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.pool(c: Offset, r: Float, color: Color) {
    drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = color.alpha * 0.4f), Color.Transparent), c, r), r, c)
}

// Pool centres (fractions of the screen) for tabs 0-2, the gallery and the compare screen.
private val POOL_A = listOf(Offset(0.1f, 0.12f), Offset(0.9f, 0.18f), Offset(0.15f, 0.82f), Offset(0.92f, 0.9f), Offset(0.5f, 0.05f))
private val POOL_B = listOf(Offset(0.95f, 0.58f), Offset(0.08f, 0.55f), Offset(0.88f, 0.3f), Offset(0.1f, 0.35f), Offset(0.05f, 0.95f))
private val POOL_C = listOf(Offset(0.3f, 0.8f), Offset(0.65f, 0.78f), Offset(0.5f, 0.02f), Offset(0.5f, 0.1f), Offset(0.95f, 0.6f))

/**
 * The page runs edge to edge under a floating pill of liquid glass. Scene and page are redrawn
 * inside the pill through [Glass.effect] (rim lens + soft blur); a blue tint and a specular rim sit
 * on top. Pressing the bar swells it a little, like a drop of water.
 */
@Composable
fun GlassScaffold(bar: @Composable () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPal.current
    val bd = LocalBackdrop.current
    val blur = Build.VERSION.SDK_INT >= 31
    val page = rememberGraphicsLayer()
    val frost = rememberGraphicsLayer()
    var barPos by remember { mutableStateOf(Offset.Zero) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    var rootH by remember { mutableIntStateOf(0) }
    var pressed by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val effect = remember(barSize, density) {
        with(density) { Glass.effect(barSize, 7.dp.toPx(), 16.dp.toPx(), barSize.height / 2f) }
    }
    val swell by animateFloatAsState(
        if (pressed) 1.03f else 1f, spring(dampingRatio = 0.4f, stiffness = 380f), label = "swell",
    )
    val space: Dp = if (barSize.height == 0) 96.dp
    else with(density) { (rootH - barPos.y).coerceAtLeast(0f).toDp() } + 10.dp
    val shape = RoundedCornerShape(50)
    Box(Modifier.fillMaxSize().onSizeChanged { rootH = it.height }) {
        Box(
            Modifier.fillMaxSize().then(
                if (blur) Modifier.drawWithContent {
                    page.record { this@drawWithContent.drawContent() }
                    drawLayer(page)
                } else Modifier,
            ),
        ) {
            CompositionLocalProvider(LocalBarSpace provides space) { content() }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                .fillMaxWidth()
                .onGloballyPositioned { c ->
                    barPos = c.positionInRoot()
                    barSize = c.size
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        do {
                            val e = awaitPointerEvent()
                        } while (e.changes.any { it.pressed })
                        pressed = false
                    }
                }
                .graphicsLayer { scaleX = swell; scaleY = swell }
                // Light mode: a navy shadow, not a blue glow, so the bar's lower edge stays crisp and lifted.
                .shadow(
                    if (p.dark) 18.dp else 12.dp, shape,
                    ambientColor = if (p.dark) p.accent.copy(alpha = 0.45f) else SHADOW.copy(alpha = 0.22f),
                    spotColor = if (p.dark) p.accent.copy(alpha = 0.35f) else SHADOW.copy(alpha = 0.38f),
                )
                .clip(shape)
                .drawBehind {
                    if (effect != null) {
                        val o = barPos
                        frost.renderEffect = effect
                        frost.record(IntSize(size.width.toInt(), size.height.toInt())) {
                            translate(-o.x, -o.y) {
                                if (bd != null) drawLayer(bd)
                                drawLayer(page)
                            }
                        }
                        drawLayer(frost)
                    }
                    val a = if (!blur) 0.95f else if (p.dark) 0.45f else 0.5f
                    drawRect(
                        Brush.verticalGradient(
                            listOf(
                                p.glass.copy(alpha = a * 0.8f),
                                p.glass.copy(alpha = a),
                                p.accent.copy(alpha = if (p.dark) 0.16f else 0.10f),
                            ),
                        ),
                    )
                    glassRim(p.dark, size.height / 2, strong = true)
                },
        ) { bar() }
    }
}

private val SHADOW = Color(0xFF14254A)

/**
 * Three tabs and a glass droplet over the current one. On a switch the droplet lifts, slides on a
 * spring (stretching with its speed) and lands with a jelly wobble. It is a lens: the icon under it
 * shows magnified. Drag along the bar to carry the droplet to any tab.
 */
@Composable
private fun NavBar() {
    val p = LocalPal.current
    val s = LocalStr.current
    val t = LocalType.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val items = listOf(Ic.pinch to s.tabCompress, Ic.history to s.tabHistory, Ic.settings to s.tabSettings)
    val n = items.size
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val icons = rememberGraphicsLayer()
    val lensLayer = rememberGraphicsLayer()
    BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp).padding(6.dp)) {
        val seg = maxWidth / n
        val density = LocalDensity.current
        val segPx = with(density) { seg.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        fun visual(i: Int) = if (rtl) n - 1 - i else i
        val x = remember { Animatable(visual(Nav.tab) * segPx) }
        val lift = remember { Animatable(1f) }
        var dragging by remember { mutableStateOf(false) }
        val landing = spring<Float>(dampingRatio = 0.68f, stiffness = 130f)
        val lens = remember(segPx, hPx) {
            with(density) { Glass.effect(IntSize(segPx.toInt(), hPx.toInt()), 0f, 5.dp.toPx(), hPx / 2) }
        }
        LaunchedEffect(Nav.tab, segPx, rtl) {
            val target = visual(Nav.tab) * segPx
            if (dragging || kotlin.math.abs(x.value - target) < 1f) return@LaunchedEffect
            coroutineScope {
                launch {
                    lift.animateTo(1.14f, tween(170, easing = FastOutSlowInEasing))
                    lift.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 170f))
                }
                x.animateTo(target, landing)
            }
        }

        fun dropletPath(): Path {
            val v = kotlin.math.abs(x.velocity)
            val stretch = (1f + v / (segPx * 9f)).coerceIn(1f, 1.35f)
            val w = segPx * lift.value * stretch
            val h = hPx * lift.value / kotlin.math.sqrt(stretch)
            val cx = x.value + segPx / 2
            return Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        cx - w / 2, hPx / 2 - h / 2, cx + w / 2, hPx / 2 + h / 2, CornerRadius(h / 2),
                    ),
                )
            }
        }

        // Icons and labels; drawn into a layer so the droplet can show them through its lens.
        Row(
            Modifier.fillMaxSize()
                .drawWithContent {
                    icons.record { this@drawWithContent.drawContent() }
                    clipPath(dropletPath(), ClipOp.Difference) { drawLayer(icons) }
                }
                .pointerInput(segPx, rtl) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            scope.launch { lift.animateTo(1.22f, spring(0.6f, 500f)) }
                        },
                        onDragEnd = {
                            dragging = false
                            val vi = (x.value / segPx).roundToInt().coerceIn(0, n - 1)
                            val tab = if (rtl) n - 1 - vi else vi
                            if (tab != Nav.tab) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            Nav.tab = tab
                            scope.launch { x.animateTo(vi * segPx, landing) }
                            scope.launch { lift.animateTo(1f, spring(0.3f, 230f)) }
                        },
                        onDragCancel = {
                            dragging = false
                            scope.launch { x.animateTo(visual(Nav.tab) * segPx, landing) }
                            scope.launch { lift.animateTo(1f, spring(0.3f, 230f)) }
                        },
                    ) { ch, _ ->
                        ch.consume()
                        val target = (ch.position.x - segPx / 2).coerceIn(0f, (n - 1) * segPx)
                        scope.launch { x.animateTo(target, spring(dampingRatio = 1f, stiffness = 1400f)) }
                    }
                },
        ) {
            items.forEachIndexed { i, (icon, label) ->
                val sel = Nav.tab == i
                val tint by animateColorAsState(if (sel) p.accent else p.text2, tween(240), label = "tint")
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50))
                        .clickable(remember { MutableInteractionSource() }, null) {
                            if (Nav.tab != i) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            Nav.tab = i
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, tint, size = 22.dp)
                    AnimatedVisibility(
                        sel,
                        enter = expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 420f)) + fadeIn(tween(200, 80)),
                        exit = shrinkHorizontally(tween(180)) + fadeOut(tween(110)),
                    ) {
                        Row {
                            Spacer(Modifier.width(6.dp))
                            Txt(label, t.small.copy(fontWeight = FontWeight.Bold), tint)
                        }
                    }
                }
            }
        }
        // The droplet: tinted glass that magnifies the icons under it.
        Box(
            Modifier
                .align(AbsoluteAlignment.TopLeft)
                .absoluteOffset { IntOffset(x.value.roundToInt(), 0) }
                .width(seg)
                .fillMaxHeight()
                .dropletMotion(x, segPx, lift)
                .clip(RoundedCornerShape(50))
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(
                                p.accent2.copy(alpha = if (p.dark) 0.32f else 0.2f),
                                p.accent.copy(alpha = if (p.dark) 0.28f else 0.18f),
                            ),
                        ),
                    )
                    // Read state here, not inside record{}: reads there are not tracked.
                    val dx = x.value
                    lensLayer.renderEffect = lens
                    lensLayer.record(IntSize(size.width.toInt(), size.height.toInt())) {
                        scale(1.08f, center) { translate(-dx, 0f) { drawLayer(icons) } }
                    }
                    drawLayer(lensLayer)
                    glassRim(p.dark, size.height / 2, strong = true)
                },
        )
    }
}
