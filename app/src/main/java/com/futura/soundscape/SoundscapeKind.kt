// app/src/main/java/com/futura/soundscape/SoundscapeKind.kt
package com.futura.soundscape

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.futura.R

/**
 * The supported ambient soundscape environments.
 *
 * Each constant carries:
 *   • [labelRes]      display name string resource.
 *   • [iconRes]       drawable for the mixer UI.
 *   • [assetPath]     path relative to `assets/soundscapes/` where the
 *                     loopable PCM file lives. Files are stereo 48 kHz,
 *                     16-bit, about 30 s loops with matching start and
 *                     end samples so they seam cleanly.
 *   • [defaultVolume] starting gain for the layer, in `[0.0, 1.0]`.
 *
 * Adding a new soundscape: drop the asset at
 * `app/src/main/assets/soundscapes/<name>.ogg`, add a constant here, and
 * add the string and drawable.
 */
enum class SoundscapeKind(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    val assetPath: String,
    val defaultVolume: Float
) {
    RAIN(
        labelRes = R.string.sleep_ambient_rain,
        iconRes = android.R.drawable.ic_menu_gallery,
        assetPath = "soundscapes/rain.ogg",
        defaultVolume = 0.6f
    ),
    VINYL_CRACKLE(
        labelRes = R.string.sleep_ambient_vinyl,
        iconRes = android.R.drawable.ic_menu_gallery,
        assetPath = "soundscapes/vinyl.ogg",
        defaultVolume = 0.4f
    ),
    CAFE(
        labelRes = R.string.sleep_ambient_cafe,
        iconRes = android.R.drawable.ic_menu_gallery,
        assetPath = "soundscapes/cafe.ogg",
        defaultVolume = 0.5f
    );

    companion object {
        fun fromName(name: String): SoundscapeKind? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
