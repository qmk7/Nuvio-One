package com.nuvio.tv.ui.intro

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import com.nuvio.tv.R
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * QR Time's opening sting: the popcorn logo pops in three times, lands with a flash of light while
 * the wordmark rises in, then zooms toward the viewer and fades into the app. 2.6 s, with the
 * owner's own recorded sound (res/raw/qr_time_intro). Shown once per app start, over the startup
 * splash, so it never adds waiting time. A tap skips it. Settings → Appearance can turn the sound
 * or the whole intro off.
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
private const val DURATION = 2.6f
private val POPS = floatArrayOf(0.00f, 0.13f, 0.22f)
private val POP_SIZES = floatArrayOf(0.42f, 0.56f, 0.68f)
private const val HIT = 0.40f
private const val ZOOM_START = 1.75f
private val NAVY_TOP = Color(0xFF071633)
private val NAVY_BOTTOM = Color(0xFF040D21)
private val GLOW = Color(0xFF5A96FF)

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
    if (t >= ZOOM_START) {
        val z = (t - ZOOM_START) / (DURATION - ZOOM_START)
        scale *= 1f + 9f * z.pow(2.4f)
    }
    return scale
}

private fun logoAlpha(t: Float): Float =
    if (t < ZOOM_START + 0.25f) 1f else (1f - (t - ZOOM_START - 0.25f) / 0.45f).coerceIn(0f, 1f)

private fun glow(t: Float): Float = if (t < HIT) 0f else exp(-(t - HIT) / 0.22f)

private fun wordmarkProgress(t: Float): Float = ((t - HIT - 0.15f) / 0.35f).coerceIn(0f, 1f)

private fun wordmarkAlpha(t: Float): Float {
    var a = wordmarkProgress(t)
    if (t > ZOOM_START) a *= (1f - (t - ZOOM_START) / 0.2f).coerceIn(0f, 1f)
    return a
}

/** The navy backdrop clears over the last moments, so the zooming logo reveals the app. */
private fun backdropAlpha(t: Float): Float = (1f - (t - 2.2f) / 0.4f).coerceIn(0f, 1f)

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
        val riseStartPx = with(density) { maxHeight.toPx() } * 0.022f
        val zoomDropPx = with(density) { maxHeight.toPx() } * 0.06f

        // backdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = backdropAlpha(time.value) }
                .background(Brush.linearGradient(listOf(NAVY_TOP, NAVY_BOTTOM)))
        )
        // flash of light behind the logo when it lands
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
                    alpha = if (s < 0.01f) 0f else logoAlpha(t)
                    translationY = if (t < ZOOM_START) 0f
                    else zoomDropPx * min((t - ZOOM_START) / 0.5f, 1f)
                }
        )
        Image(
            painter = painterResource(R.drawable.livetv_wordmark_iptv),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(BiasAlignment(0f, 0.62f))
                .width(wordmarkWidth)
                .graphicsLayer {
                    val t = time.value
                    alpha = wordmarkAlpha(t)
                    translationY = riseStartPx * (1f - wordmarkProgress(t))
                }
        )
    }
}
