package com.nuvio.tv.ui.intro

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import com.nuvio.tv.R
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * QR Time's opening intro, timed to the owner's own recording (res/raw/qr_time_intro):
 * the popcorn logo pops in three times and lands with a soft flash on the opening hit while the
 * wordmark rises in; then, carried by the music, it quietly comes apart into pieces (no lines,
 * just small gaps opening up, faster on the louder notes); when the music ends the pieces let go
 * one after another and fall away gently, fading, revealing the app. 3 s, once per app start,
 * drawn over the startup splash so it adds no waiting. A tap skips it. Settings → Appearance can
 * turn the sound or the whole intro off.
 */
object QrTimeIntro {
    private const val PREFS = "qr_time_intro"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SOUND = "sound"

    /** Once per process: coming back from the background or a configuration change never replays it. */
    @Volatile
    internal var shownThisProcess = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    fun isSoundEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SOUND, true)
    fun setSoundEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_SOUND, enabled).apply()
}

private const val TAG = "QrTimeIntro"
private const val DURATION = 3.0f
private val POPS = floatArrayOf(0.00f, 0.13f, 0.22f)
private val POP_SIZES = floatArrayOf(0.42f, 0.56f, 0.68f)
private const val HIT = 0.40f
/** Pieces start to separate once the landing bounce has settled. */
private const val APART = 0.75f
/** The music has died out: the pieces let go. */
private const val FALL = 1.93f
/** Soft gravity, in logo sizes per second squared. */
private const val GRAVITY = 2.7f
private val NAVY_TOP = Color(0xFF071633)
private val NAVY_BOTTOM = Color(0xFF040D21)
private val GLOW = Color(0xFF5A96FF)

/** Loudness of the intro recording, 30 values per second (0..1), measured from the sound file. */
private val LEVEL = floatArrayOf(
    0.06f, 0.07f, 0.10f, 0.15f, 0.14f, 0.13f, 0.18f, 0.22f, 0.25f, 0.20f, 0.23f, 1.00f, 0.78f, 0.61f,
    0.66f, 0.71f, 0.72f, 0.65f, 0.54f, 0.63f, 0.61f, 0.56f, 0.55f, 0.63f, 0.66f, 0.62f, 0.49f, 0.96f,
    1.00f, 0.68f, 0.48f, 0.92f, 0.98f, 0.72f, 0.38f, 0.57f, 0.58f, 0.45f, 0.42f, 0.59f, 0.56f, 0.51f,
    0.34f, 0.45f, 0.57f, 0.49f, 0.46f, 0.49f, 0.50f, 0.41f, 0.34f, 0.29f, 0.29f, 0.24f, 0.11f, 0.12f,
    0.10f, 0.10f, 0.05f, 0.03f, 0.02f, 0.01f, 0.01f
)

/** Accumulated loudness from APART, normalised so it reaches 1 when the music ends. */
private val APART_CURVE: FloatArray = run {
    val cumulative = FloatArray(LEVEL.size + 1)
    for (i in 1..LEVEL.size) {
        cumulative[i] = cumulative[i - 1] + if (i / 30f > APART) LEVEL[i - 1] else 0f
    }
    val total = cumulative[min(cumulative.size - 1, (FALL * 30).toInt())].takeIf { it > 0f } ?: 1f
    FloatArray(cumulative.size) { (cumulative[it] / total).coerceIn(0f, 1f) }
}

private fun smooth(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3 - 2 * c)
}

private fun easeOutBack(x: Float, s: Float = 2.2f): Float {
    val t = x - 1f
    return t * t * ((s + 1) * t + s) + 1f
}

private fun bump(t: Float, at: Float, length: Float): Float =
    if (t < at) 0f else min(easeOutBack(min((t - at) / length, 1f)), 1.3f)

private fun logoScale(t: Float): Float {
    if (t < POPS[0]) return 0f
    var scale = 0f
    var previous = 0f
    for (i in POPS.indices) {
        if (t >= POPS[i]) {
            scale = previous + (POP_SIZES[i] - previous) * bump(t, POPS[i], 0.09f)
            previous = POP_SIZES[i]
        }
    }
    if (t >= HIT) scale = previous + (1f - previous) * bump(t, HIT, 0.32f)
    return scale
}

/** 0 (whole) .. 1 (fully apart), driven by the music. */
private fun apartProgress(t: Float): Float =
    smooth(APART_CURVE[(t * 30).toInt().coerceIn(0, APART_CURVE.size - 1)])

private fun glow(t: Float): Float = if (t < HIT) 0f else exp(-(t - HIT) / 0.22f)

private fun wordmarkProgress(t: Float): Float = ((t - HIT - 0.15f) / 0.35f).coerceIn(0f, 1f)

private fun wordmarkFall(t: Float): Float = if (t <= FALL) 0f else smooth((t - FALL) / 0.7f)

/** The navy backdrop gives way to the app at the very end. */
private fun backdropAlpha(t: Float): Float = 1f - smooth((t - 2.45f) / 0.5f)

/** One piece of the logo, in the logo's own unit square (0..1). */
private class Piece(
    val path: Path,
    val center: Offset,
    val dirX: Float,
    val dirY: Float,
    val separation: Float,
    val tilt: Float,
    val delay: Float,
    val vx: Float,
    val vy: Float,
    val spin: Float
)

/** The logo square cut into triangles over a jittered 5×5 grid, always the same cut. */
private fun buildPieces(): List<Piece> {
    val random = Random(9)
    val grid = 5
    val points = Array(grid + 1) { i ->
        Array(grid + 1) { j ->
            var x = i / grid.toFloat()
            var y = j / grid.toFloat()
            if (i in 1 until grid) x += (random.nextFloat() * 0.56f - 0.28f) / grid
            if (j in 1 until grid) y += (random.nextFloat() * 0.56f - 0.28f) / grid
            Offset(x, y)
        }
    }
    val pieces = mutableListOf<Piece>()
    fun add(a: Offset, b: Offset, c: Offset) {
        val center = Offset((a.x + b.x + c.x) / 3f, (a.y + b.y + c.y) / 3f)
        val ox = center.x - 0.5f
        val oy = center.y - 0.5f
        val dist = hypot(ox, oy).coerceAtLeast(1e-4f)
        pieces += Piece(
            path = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() },
            center = center,
            dirX = ox / dist,
            dirY = oy / dist,
            separation = 0.015f + random.nextFloat() * 0.018f,
            tilt = random.nextFloat() * 10f - 5f,
            // lower pieces let go first, then the rest follow
            delay = (1f - center.y) * 0.28f + random.nextFloat() * 0.12f,
            vx = random.nextFloat() * 0.24f - 0.12f + ox * 0.25f,
            vy = random.nextFloat() * 0.15f - 0.09f,
            spin = random.nextFloat() * 140f - 70f
        )
    }
    for (i in 0 until grid) {
        for (j in 0 until grid) {
            val a = points[i][j]
            val b = points[i + 1][j]
            val c = points[i + 1][j + 1]
            val d = points[i][j + 1]
            if (random.nextBoolean()) { add(a, b, c); add(a, c, d) } else { add(a, b, d); add(b, c, d) }
        }
    }
    return pieces
}

private fun startSound(context: Context): MediaPlayer? = runCatching {
    MediaPlayer.create(
        context,
        R.raw.qr_time_intro,
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build(),
        0
    )?.apply {
        setOnCompletionListener { runCatching { it.release() } }
        start()
    }
}.onFailure { Log.w(TAG, "intro sound failed", it) }.getOrNull()

/** Draw this last in the app's root so it covers everything while it plays. */
@Composable
fun QrTimeIntroOverlay() {
    val context = LocalContext.current
    var visible by remember {
        mutableStateOf(!QrTimeIntro.shownThisProcess && QrTimeIntro.isEnabled(context))
    }
    if (!visible) return
    val time = remember { Animatable(0f) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val pieces = remember { buildPieces() }
    val logoBitmap: ImageBitmap = ImageBitmap.imageResource(R.drawable.nuvio_one_icon)
    val logoBrush = remember(logoBitmap) { ShaderBrush(ImageShader(logoBitmap, TileMode.Clamp, TileMode.Clamp)) }

    LaunchedEffect(Unit) {
        QrTimeIntro.shownThisProcess = true
        if (QrTimeIntro.isSoundEnabled(context)) player = startSound(context)
        time.animateTo(DURATION, tween((DURATION * 1000).toInt(), easing = LinearEasing))
        visible = false
    }
    DisposableEffect(Unit) {
        onDispose {
            // Skipped or left early: stop the sound with the picture. (After it finished, the
            // completion listener has already released it; these calls then just fail quietly.)
            player?.let { runCatching { if (it.isPlaying) it.stop() }; runCatching { it.release() } }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { visible = false } }
    ) {
        val density = LocalDensity.current
        val logoSize = maxHeight * 0.46f
        val wordmarkWidth = maxWidth * 0.26f
        val screenHeightPx = with(density) { maxHeight.toPx() }

        // backdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = backdropAlpha(time.value) }
                .background(Brush.linearGradient(listOf(NAVY_TOP, NAVY_BOTTOM)))
        )
        // soft flash of light behind the logo when it lands
        Box(
            modifier = Modifier
                .align(BiasAlignment(0f, -0.12f))
                .size(maxHeight * 1.2f)
                .drawBehind {
                    val g = glow(time.value)
                    if (g > 0.01f) {
                        val radius = size.minDimension / 2f * (0.42f + 0.58f * (1f - g))
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(GLOW.copy(alpha = 0.6f * g), Color.Transparent),
                                center = center,
                                radius = radius
                            ),
                            radius = radius
                        )
                    }
                }
        )
        // the whole logo, until it starts to come apart
        Image(
            painter = painterResource(R.drawable.nuvio_one_icon),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(BiasAlignment(0f, -0.12f))
                .size(logoSize)
                .graphicsLayer {
                    val t = time.value
                    val s = logoScale(t)
                    scaleX = s
                    scaleY = s
                    alpha = if (s < 0.01f || t >= APART) 0f else 1f
                }
        )
        // the logo in pieces: separating with the music, then falling away gently
        Canvas(
            modifier = Modifier
                .align(BiasAlignment(0f, -0.12f))
                .size(logoSize)
        ) {
            val t = time.value
            if (t < APART) return@Canvas
            val side = size.width
            val k = apartProgress(t)
            val toBitmap = logoBitmap.width / side
            for (piece in pieces) {
                var dx = piece.dirX * piece.separation * k * side
                var dy = piece.dirY * piece.separation * k * side
                var angle = piece.tilt * k
                var alpha = 1f
                val fallTime = t - FALL - piece.delay
                if (fallTime > 0f) {
                    dx += piece.vx * fallTime * side
                    dy += (piece.vy * fallTime + 0.5f * GRAVITY * fallTime * fallTime) * side
                    angle += piece.spin * fallTime
                    alpha = 1f - smooth((fallTime - 0.25f) / 0.6f)
                }
                if (alpha <= 0.01f) continue
                val pivot = Offset(piece.center.x * side, piece.center.y * side)
                translate(dx, dy) {
                    rotate(angle, pivot) {
                        // Paint the triangle with the logo image (anti-aliased edges, no seams drawn).
                        scale(1f / toBitmap, pivot = Offset.Zero) {
                            val bitmapPath = Path().apply {
                                addPath(piece.path)
                                transform(androidx.compose.ui.graphics.Matrix().apply {
                                    scale(logoBitmap.width.toFloat(), logoBitmap.height.toFloat())
                                })
                            }
                            drawPath(bitmapPath, brush = logoBrush, alpha = alpha)
                        }
                    }
                }
            }
        }
        Image(
            painter = painterResource(R.drawable.livetv_wordmark_iptv),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(BiasAlignment(0f, 0.62f))
                .width(wordmarkWidth)
                .graphicsLayer {
                    val t = time.value
                    val fall = wordmarkFall(t)
                    alpha = wordmarkProgress(t) * (1f - fall)
                    translationY = screenHeightPx * (0.022f * (1f - wordmarkProgress(t)) + 0.042f * fall)
                }
        )
    }
}
