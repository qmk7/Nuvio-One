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
import kotlin.math.min

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
private const val GRAVITY = 2.719f
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

/**
 * The approved cut: 47 triangles over a jittered 5×5 grid, with each piece's motion, exported from
 * the preview the owner approved so the app plays exactly that. Per row, in logo units (0..1):
 * triangle ax, ay, bx, by, cx, cy; pivot x, y; outward direction x, y; separation; tilt (deg);
 * fall delay (s); fall velocity x, y (logo sizes/s); spin (deg/s).
 */
private val PIECE_TABLE: Array<FloatArray> = arrayOf(
    floatArrayOf(0.0000f, 0.0000f, 0.1447f, 0.0000f, 0.2003f, 0.2446f, 0.1647f, 0.1934f, -0.7380f, -0.6748f, 0.0323f, -2.6321f, 0.2992f, -0.1943f, -0.0354f, 24.3775f),
    floatArrayOf(0.0000f, 0.0000f, 0.2003f, 0.2446f, 0.0000f, 0.1959f, 0.1450f, 0.2009f, -0.7647f, -0.6443f, 0.0258f, 2.7463f, 0.2342f, -0.1257f, 0.0399f, 11.7796f),
    floatArrayOf(0.0000f, 0.1959f, 0.2003f, 0.2446f, 0.1531f, 0.4061f, 0.1329f, 0.3127f, -0.8907f, -0.4545f, 0.0233f, -0.9783f, 0.3108f, -0.0738f, -0.0879f, 41.9118f),
    floatArrayOf(0.0000f, 0.1959f, 0.1531f, 0.4061f, 0.0000f, 0.3858f, 0.1133f, 0.3505f, -0.9327f, -0.3607f, 0.0211f, -0.6643f, 0.2075f, -0.1102f, -0.0416f, -57.5657f),
    floatArrayOf(0.0000f, 0.3858f, 0.1531f, 0.4061f, 0.2131f, 0.5486f, 0.1556f, 0.4743f, -0.9972f, -0.0744f, 0.0265f, -3.9693f, 0.2413f, -0.2008f, 0.0273f, 43.0570f),
    floatArrayOf(0.0000f, 0.3858f, 0.2131f, 0.5486f, 0.0000f, 0.5595f, 0.1616f, 0.5106f, -0.9995f, 0.0312f, 0.0241f, 2.0945f, 0.1668f, -0.0272f, -0.0264f, -37.6665f),
    floatArrayOf(0.0000f, 0.5595f, 0.2131f, 0.5486f, 0.0000f, 0.8411f, 0.1707f, 0.5952f, -0.9607f, 0.2776f, 0.0326f, -0.9910f, 0.1581f, 0.0047f, -0.0348f, 23.4508f),
    floatArrayOf(0.2131f, 0.5486f, 0.1865f, 0.8228f, 0.0000f, 0.8411f, 0.1798f, 0.6858f, -0.8650f, 0.5018f, 0.0182f, 3.4327f, 0.1190f, -0.1887f, 0.0567f, -45.8141f),
    floatArrayOf(0.1447f, 0.0000f, 0.4252f, 0.0000f, 0.3616f, 0.1707f, 0.3293f, 0.1073f, -0.3986f, -0.9171f, 0.0323f, 4.8617f, 0.3228f, -0.1606f, -0.0814f, -40.7957f),
    floatArrayOf(0.1447f, 0.0000f, 0.3616f, 0.1707f, 0.2003f, 0.2446f, 0.2704f, 0.1662f, -0.5667f, -0.8239f, 0.0222f, 1.1155f, 0.3494f, -0.0926f, -0.0694f, 8.6705f),
    floatArrayOf(0.2003f, 0.2446f, 0.3616f, 0.1707f, 0.3564f, 0.4007f, 0.2810f, 0.2855f, -0.7145f, -0.6997f, 0.0176f, -4.1341f, 0.2668f, -0.0074f, -0.0807f, -6.8039f),
    floatArrayOf(0.2003f, 0.2446f, 0.3564f, 0.4007f, 0.1531f, 0.4061f, 0.2538f, 0.3248f, -0.8147f, -0.5798f, 0.0279f, 2.6417f, 0.2350f, 0.0320f, -0.0651f, 30.1653f),
    floatArrayOf(0.1531f, 0.4061f, 0.3564f, 0.4007f, 0.2131f, 0.5486f, 0.2538f, 0.4743f, -0.9946f, -0.1037f, 0.0291f, 3.8026f, 0.2065f, -0.1584f, -0.0833f, 4.0373f),
    floatArrayOf(0.3564f, 0.4007f, 0.4475f, 0.6101f, 0.2131f, 0.5486f, 0.3308f, 0.5045f, -0.9996f, 0.0268f, 0.0182f, 1.2968f, 0.1488f, 0.0254f, -0.0570f, -68.1780f),
    floatArrayOf(0.2131f, 0.5486f, 0.4475f, 0.6101f, 0.4307f, 0.7870f, 0.3308f, 0.6677f, -0.7103f, 0.7039f, 0.0183f, -0.4330f, 0.1602f, -0.0693f, -0.0645f, -2.5114f),
    floatArrayOf(0.2131f, 0.5486f, 0.4307f, 0.7870f, 0.1865f, 0.8228f, 0.3082f, 0.6858f, -0.7183f, 0.6957f, 0.0323f, 0.3543f, 0.2009f, -0.1619f, 0.0594f, 54.4592f),
    floatArrayOf(0.1865f, 0.8228f, 0.4307f, 0.7870f, 0.4276f, 1.0000f, 0.3097f, 0.8716f, -0.4559f, 0.8900f, 0.0250f, 0.2350f, 0.1004f, 0.0514f, -0.0807f, 19.9935f),
    floatArrayOf(0.1865f, 0.8228f, 0.4276f, 1.0000f, 0.1946f, 1.0000f, 0.2779f, 0.8897f, -0.4951f, 0.8689f, 0.0249f, -1.9968f, 0.1179f, -0.0022f, -0.0750f, 27.9329f),
    floatArrayOf(0.4252f, 0.0000f, 0.5554f, 0.0000f, 0.3616f, 0.1707f, 0.4063f, 0.1163f, -0.2371f, -0.9715f, 0.0233f, -0.0978f, 0.3238f, -0.1315f, 0.0004f, -17.7600f),
    floatArrayOf(0.5554f, 0.0000f, 0.5766f, 0.2195f, 0.3616f, 0.1707f, 0.4683f, 0.1526f, -0.0909f, -0.9959f, 0.0310f, -2.6889f, 0.3361f, 0.0476f, 0.0038f, 52.6189f),
    floatArrayOf(0.3616f, 0.1707f, 0.5766f, 0.2195f, 0.6253f, 0.3912f, 0.4924f, 0.2810f, -0.0345f, -0.9994f, 0.0158f, 0.9696f, 0.2749f, 0.0411f, -0.0292f, -60.3449f),
    floatArrayOf(0.3616f, 0.1707f, 0.6253f, 0.3912f, 0.3564f, 0.4007f, 0.4894f, 0.2855f, -0.0492f, -0.9988f, 0.0185f, 1.0812f, 0.2218f, -0.1078f, -0.0370f, -4.1662f),
    floatArrayOf(0.3564f, 0.4007f, 0.6253f, 0.3912f, 0.4475f, 0.6101f, 0.4894f, 0.5000f, -1.0000f, 0.0000f, 0.0248f, -4.7403f, 0.2331f, -0.0428f, 0.0276f, -68.8001f),
    floatArrayOf(0.6253f, 0.3912f, 0.5538f, 0.5739f, 0.4475f, 0.6101f, 0.5363f, 0.5000f, 1.0000f, 0.0000f, 0.0324f, 0.9025f, 0.2572f, 0.1266f, 0.0352f, -55.1271f),
    floatArrayOf(0.4475f, 0.6101f, 0.5538f, 0.5739f, 0.5675f, 0.7755f, 0.5076f, 0.6737f, 0.0434f, 0.9991f, 0.0214f, -2.6868f, 0.1850f, -0.0725f, -0.0572f, -54.5151f),
    floatArrayOf(0.4475f, 0.6101f, 0.5675f, 0.7755f, 0.4307f, 0.7870f, 0.4985f, 0.6979f, -0.0076f, 1.0000f, 0.0173f, 4.3813f, 0.2017f, -0.0312f, 0.0213f, -4.5487f),
    floatArrayOf(0.4307f, 0.7870f, 0.5675f, 0.7755f, 0.4276f, 1.0000f, 0.4970f, 0.8656f, -0.0083f, 1.0000f, 0.0246f, -1.3051f, 0.1138f, -0.0645f, -0.0519f, 2.4958f),
    floatArrayOf(0.5675f, 0.7755f, 0.6347f, 1.0000f, 0.4276f, 1.0000f, 0.5378f, 0.8656f, 0.1028f, 0.9947f, 0.0187f, -0.6074f, 0.1509f, -0.1060f, -0.0750f, 41.9958f),
    floatArrayOf(0.5554f, 0.0000f, 0.7663f, 0.0000f, 0.5766f, 0.2195f, 0.5846f, 0.1858f, 0.2600f, -0.9656f, 0.0162f, -2.4876f, 0.3302f, 0.0466f, -0.0573f, -61.7947f),
    floatArrayOf(0.7663f, 0.0000f, 0.8433f, 0.2425f, 0.5766f, 0.2195f, 0.6949f, 0.1752f, 0.5145f, -0.8575f, 0.0201f, -1.9985f, 0.3270f, 0.1637f, 0.0281f, 56.2450f),
    floatArrayOf(0.5766f, 0.2195f, 0.8433f, 0.2425f, 0.7501f, 0.3864f, 0.6798f, 0.3021f, 0.6724f, -0.7402f, 0.0320f, 3.7658f, 0.3058f, 0.1579f, -0.0764f, -25.5250f),
    floatArrayOf(0.5766f, 0.2195f, 0.7501f, 0.3864f, 0.6253f, 0.3912f, 0.6631f, 0.3051f, 0.6419f, -0.7668f, 0.0196f, 1.0417f, 0.2536f, 0.0038f, 0.0358f, -14.1272f),
    floatArrayOf(0.6253f, 0.3912f, 0.7501f, 0.3864f, 0.5538f, 0.5739f, 0.6526f, 0.4789f, 0.9905f, -0.1373f, 0.0189f, -2.2361f, 0.2013f, 0.0648f, -0.0048f, -8.4093f),
    floatArrayOf(0.7501f, 0.3864f, 0.7991f, 0.5466f, 0.5538f, 0.5739f, 0.6767f, 0.4789f, 0.9929f, -0.1188f, 0.0185f, -0.9783f, 0.1849f, -0.0354f, -0.0085f, -43.6013f),
    floatArrayOf(0.5538f, 0.5739f, 0.7991f, 0.5466f, 0.5675f, 0.7755f, 0.6767f, 0.6601f, 0.7411f, 0.6714f, 0.0280f, 1.5876f, 0.1117f, 0.0745f, -0.0396f, -7.4093f),
    floatArrayOf(0.7991f, 0.5466f, 0.7916f, 0.8455f, 0.5675f, 0.7755f, 0.6828f, 0.6949f, 0.6841f, 0.7294f, 0.0261f, 2.6160f, 0.1915f, 0.1021f, -0.0587f, 5.8715f),
    floatArrayOf(0.5675f, 0.7755f, 0.7916f, 0.8455f, 0.7565f, 1.0000f, 0.6782f, 0.8505f, 0.4534f, 0.8913f, 0.0212f, -4.8088f, 0.1082f, 0.0375f, 0.0403f, -24.6837f),
    floatArrayOf(0.5675f, 0.7755f, 0.7565f, 1.0000f, 0.6347f, 1.0000f, 0.6299f, 0.8656f, 0.3349f, 0.9423f, 0.0302f, 0.1414f, 0.0495f, 0.0849f, -0.0476f, 4.6902f),
    floatArrayOf(0.7663f, 0.0000f, 1.0000f, 0.0000f, 1.0000f, 0.2108f, 0.9033f, 0.0967f, 0.7071f, -0.7071f, 0.0164f, -4.8920f, 0.3634f, -0.0099f, -0.0681f, -47.1382f),
    floatArrayOf(0.7663f, 0.0000f, 1.0000f, 0.2108f, 0.8433f, 0.2425f, 0.8520f, 0.1224f, 0.6818f, -0.7315f, 0.0168f, 3.2407f, 0.3652f, 0.1240f, -0.0309f, 54.3812f),
    floatArrayOf(0.8433f, 0.2425f, 1.0000f, 0.2108f, 0.7501f, 0.3864f, 0.7825f, 0.3610f, 0.8973f, -0.4414f, 0.0243f, -3.6516f, 0.2617f, 0.0365f, -0.0499f, -25.1377f),
    floatArrayOf(1.0000f, 0.2108f, 1.0000f, 0.3576f, 0.7501f, 0.3864f, 0.8036f, 0.3640f, 0.9127f, -0.4087f, 0.0221f, -3.1757f, 0.2516f, 0.1649f, -0.0044f, -68.6770f),
    floatArrayOf(0.7501f, 0.3864f, 1.0000f, 0.3576f, 0.7991f, 0.5466f, 0.8097f, 0.4607f, 0.9921f, -0.1258f, 0.0275f, -4.7988f, 0.2085f, 0.1658f, 0.0117f, 57.1495f),
    floatArrayOf(1.0000f, 0.3576f, 1.0000f, 0.6088f, 0.7991f, 0.5466f, 0.8187f, 0.5302f, 0.9955f, 0.0944f, 0.0288f, -2.2719f, 0.1488f, 0.0680f, 0.0121f, -37.5121f),
    floatArrayOf(0.7991f, 0.5466f, 1.0000f, 0.6088f, 1.0000f, 0.8443f, 0.8142f, 0.5665f, 0.9783f, 0.2070f, 0.0211f, 2.4812f, 0.1631f, 0.1029f, -0.0082f, 60.0422f),
    floatArrayOf(0.7991f, 0.5466f, 1.0000f, 0.8443f, 0.7916f, 0.8455f, 0.8097f, 0.6949f, 0.8464f, 0.5326f, 0.0156f, -3.1359f, 0.1789f, 0.1183f, -0.0760f, -51.1630f),
    floatArrayOf(0.7916f, 0.8455f, 1.0000f, 0.8443f, 0.7565f, 1.0000f, 0.7885f, 0.8671f, 0.6180f, 0.7862f, 0.0189f, 0.4313f, 0.1455f, 0.0036f, -0.0600f, 26.0097f),
)

private fun buildPieces(): List<Piece> = PIECE_TABLE.map { r ->
    Piece(
        path = Path().apply { moveTo(r[0], r[1]); lineTo(r[2], r[3]); lineTo(r[4], r[5]); close() },
        center = Offset(r[6], r[7]),
        dirX = r[8],
        dirY = r[9],
        separation = r[10],
        tilt = r[11],
        delay = r[12],
        vx = r[13],
        vy = r[14],
        spin = r[15]
    )
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
                    // The approved preview turned pieces counter-clockwise for positive angles
                    // (PIL); Compose turns clockwise, so flip the sign to match it.
                    rotate(-angle, pivot) {
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
