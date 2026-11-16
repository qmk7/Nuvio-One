package com.nuvio.tv.core.device

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * What kind of device QR Time is running on. Screens read it (through [LocalDeviceType] in Compose,
 * or [DeviceTypes.current] elsewhere) to pick their layout and input style.
 */
enum class DeviceType {
    /** Android TV, Google TV, Fire TV: remote control, D-pad focus, 10-foot layout. */
    TV,

    /** Touch screen, smallest side at least 600dp. */
    TABLET,

    /** Touch screen, smallest side under 600dp. */
    PHONE;

    val isTv: Boolean get() = this == TV

    /** Phones and tablets are driven by touch rather than a remote. */
    val isTouch: Boolean get() = this != TV
}

object DeviceTypes {
    /** Same threshold Android uses for its sw600dp tablet resources. */
    const val TABLET_MIN_SMALLEST_WIDTH_DP = 600

    @Volatile
    private var tvCache: Boolean? = null

    /** The device type for the current screen configuration. */
    fun current(context: Context): DeviceType =
        resolve(isTelevision(context), context.resources.configuration.smallestScreenWidthDp)

    /**
     * TV is a fixed hardware fact; phone vs tablet comes from the screen, so a foldable that opens
     * from phone size to tablet size switches layout.
     */
    fun resolve(isTelevision: Boolean, smallestScreenWidthDp: Int): DeviceType = when {
        isTelevision -> DeviceType.TV
        smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP -> DeviceType.TABLET
        else -> DeviceType.PHONE
    }

    /** True on Android TV / Google TV / Fire TV boxes and televisions. */
    @Suppress("DEPRECATION") // FEATURE_TELEVISION: still reported by some older TV boxes
    fun isTelevision(context: Context): Boolean {
        tvCache?.let { return it }
        val app = context.applicationContext ?: context
        val pm = app.packageManager
        val uiModeManager = app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val result = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            pm.hasSystemFeature(AMAZON_FIRE_TV_FEATURE)
        tvCache = result
        return result
    }

    private const val AMAZON_FIRE_TV_FEATURE = "amazon.hardware.fire_tv"
}

/** The device type for the whole UI. Provided once at the root of MainActivity's content. */
val LocalDeviceType = staticCompositionLocalOf { DeviceType.TV }

/** Works out the device type from the live configuration, so it follows foldables and resizes. */
@Composable
@ReadOnlyComposable
fun currentDeviceType(): DeviceType {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return DeviceTypes.resolve(DeviceTypes.isTelevision(context), configuration.smallestScreenWidthDp)
}
