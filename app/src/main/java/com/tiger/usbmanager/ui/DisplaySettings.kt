package com.tiger.usbmanager.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.google.android.material.color.ColorResourcesOverride
import com.google.android.material.color.utilities.*
import com.tiger.usbmanager.ModuleSettings
import com.tiger.usbmanager.R

/** Own View-based implementation: seed palettes, system colors and persisted appearance. */
@android.annotation.SuppressLint("RestrictedApi") // Isolated adapter to the project's pinned Material 1.14 palette/resource APIs.
internal object DisplaySettings {
    const val DEFAULT_SEED = 0xFF3478F6.toInt()
    private const val FLOATING = "display_floating_bar"
    private const val VERTICAL = "display_vertical_layout"
    private const val DYNAMIC = "display_dynamic_colors"
    private const val SEED = "display_seed_color"
    private const val MODE = "display_theme_mode"
    private const val FLOATING_POSITION = "display_floating_position"
    private const val STANDARD_POSITION = "display_standard_position"
    private const val BAR_SIDE = "display_navigation_side"
    private const val FLOATING_BOTTOM = "display_floating_bottom_position"
    enum class FloatingPosition(val label: Int, val side: Int = 0) {
        BOTTOM_CENTER(R.string.bar_bottom_center), BOTTOM_RIGHT(R.string.bar_bottom_right),
        LEFT_CENTER(R.string.bar_left_center, -1), RIGHT_CENTER(R.string.bar_right_center, 1)
    }
    enum class StandardPosition(val label: Int, val side: Int = 0) {
        BOTTOM(R.string.bar_bottom), LEFT(R.string.bar_left, -1), RIGHT(R.string.bar_right, 1)
    }
    enum class Mode(val label: Int) {
        SYSTEM(R.string.theme_mode_system), LIGHT(R.string.theme_mode_light), DARK(R.string.theme_mode_dark)
    }
    private data class Key(val seed: Int, val dark: Boolean, val dynamic: Boolean)
    private val cache = mutableMapOf<Key, Map<Int, Int>>()

    fun floating() = ModuleSettings.prefs().getBoolean(FLOATING, false)
    fun verticalLayout() = ModuleSettings.prefs().getBoolean(VERTICAL, false)
    fun dynamic() = ModuleSettings.prefs().getBoolean(DYNAMIC, false)
    fun dynamicAvailable() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    fun mode() = Mode.entries.firstOrNull { it.name == ModuleSettings.prefs().getString(MODE, null) } ?: Mode.SYSTEM
    fun seed(): Int {
        val prefs = ModuleSettings.prefs()
        if (prefs.contains(SEED)) return prefs.getInt(SEED, DEFAULT_SEED) or 0xFF000000.toInt()
        return when (prefs.getString("display_theme_color", null)) {
            "GREEN" -> 0xFF25764F.toInt(); "PURPLE" -> 0xFF7454B5.toInt()
            "ORANGE" -> 0xFFA85519.toInt(); "ROSE" -> 0xFFB4426B.toInt()
            "TEAL" -> 0xFF007B83.toInt(); else -> DEFAULT_SEED
        }
    }
    fun setFloating(value: Boolean) {
        // Resolve legacy independent positions before switching which type is enabled.
        val side = storedNavigationSide()
        ModuleSettings.prefs().edit { putInt(BAR_SIDE, side); putBoolean(FLOATING, value) }
    }
    private fun storedNavigationSide(): Int {
        val prefs = ModuleSettings.prefs()
        if (prefs.contains(BAR_SIDE)) return prefs.getInt(BAR_SIDE, 0).coerceIn(-1, 1)
        // Migrate using the position of the currently enabled navigation type.
        return if (floating()) FloatingPosition.entries.firstOrNull {
            it.name == prefs.getString(FLOATING_POSITION, null)
        }?.side ?: 0 else StandardPosition.entries.firstOrNull {
            it.name == prefs.getString(STANDARD_POSITION, null)
        }?.side ?: 0
    }
    fun floatingPosition(): FloatingPosition = when (storedNavigationSide()) {
        -1 -> FloatingPosition.LEFT_CENTER
        1 -> FloatingPosition.RIGHT_CENTER
        else -> {
            val prefs = ModuleSettings.prefs()
            FloatingPosition.entries.firstOrNull {
                it.side == 0 && it.name == prefs.getString(FLOATING_BOTTOM, prefs.getString(FLOATING_POSITION, null))
            } ?: FloatingPosition.BOTTOM_RIGHT
        }
    }
    fun standardPosition() = StandardPosition.entries.first { it.side == storedNavigationSide() }
    fun setFloatingPosition(value: FloatingPosition) = ModuleSettings.prefs().edit {
        putInt(BAR_SIDE, value.side)
        if (value.side == 0) putString(FLOATING_BOTTOM, value.name)
    }
    fun setStandardPosition(value: StandardPosition) = ModuleSettings.prefs().edit { putInt(BAR_SIDE, value.side) }
    fun navigationSide(context: Context): Int = if (!context.landscape()) 0
        else if (floating()) floatingPosition().side else standardPosition().side
    fun setVerticalLayout(value: Boolean) = ModuleSettings.prefs().edit { putBoolean(VERTICAL, value) }
    fun setDynamic(value: Boolean) = ModuleSettings.prefs().edit { putBoolean(DYNAMIC, value) }
    fun setSeed(value: Int) = ModuleSettings.prefs().edit { putInt(SEED, value or 0xFF000000.toInt()) }
    fun setMode(value: Mode) = ModuleSettings.prefs().edit { putString(MODE, value.name) }
    fun hex(value: Int) = "#%06X".format(java.util.Locale.ROOT, value and 0xFFFFFF)

    /** Apply to the Activity itself before its theme/resources are first accessed. */
    fun nightConfiguration(): Configuration = Configuration().apply {
        uiMode = when (mode()) {
            Mode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            Mode.DARK -> Configuration.UI_MODE_NIGHT_YES
            Mode.SYSTEM -> 0 // Undefined: inherit the current system configuration.
        }
    }

    fun wrapContext(base: Context): Context {
        ModuleSettings.init(base)
        // Override only night mode: copying the full configuration freezes orientation
        // and window dimensions in the chooser's onConfigurationChanged path.
        val configuration = nightConfiguration()
        val context = base.createConfigurationContext(configuration)
        // Isolate resource overrides per activity; XML and dialogs receive the same palette.
        return runCatching {
            (ColorResourcesOverride.getInstance()?.wrapContextIfPossible(context, colors(context)) ?: context).also {
                // Replace Material's personalized-color overlay with this app's complete color roles.
                it.theme.applyStyle(if (isDark(context)) R.style.ThemeOverlay_USBManager_Colors_Dark
                    else R.style.ThemeOverlay_USBManager_Colors_Light, true)
            }
        }
            .onFailure { android.util.Log.w("USBManager", "Palette resource override unavailable", it) }.getOrDefault(context)
    }

    fun applyOrientation(activity: Activity, unlockedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
        val orientation = if (verticalLayout()) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else unlockedOrientation
        if (activity.requestedOrientation != orientation) {
            runCatching { activity.requestedOrientation = orientation }
                .onFailure { android.util.Log.w("USBManager", "Window rejected the requested orientation", it) }
        }
    }

    fun apply(activity: Activity, unlockedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
        ModuleSettings.init(activity)
        // The manifest theme is applied after attachBaseContext and can replace the
        // wrapped context's colors. Apply our complete overlay to the Activity too,
        // before its window, native widgets or dialog contexts resolve attributes.
        activity.theme.applyStyle(if (isDark(activity)) R.style.ThemeOverlay_USBManager_Colors_Dark
            else R.style.ThemeOverlay_USBManager_Colors_Light, true)
        applyOrientation(activity, unlockedOrientation)
        val dark = isDark(activity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The app provides its own light/dark colors; prevent automatic recoloring.
            activity.window.decorView.isForceDarkAllowed = false
        }
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = Color.TRANSPARENT
    }
    fun isDark(context: Context): Boolean = when (mode()) {
        Mode.LIGHT -> false
        Mode.DARK -> true
        Mode.SYSTEM -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    @android.annotation.SuppressLint("ResourceAsColor") // Resource IDs index the palette; values are resolved ARGB colors.
    fun colors(context: Context, previewSeed: Int? = null): Map<Int, Int> {
        val dynamic = previewSeed == null && dynamic() && dynamicAvailable()
        val source = previewSeed ?: if (dynamic) context.getColor(android.R.color.system_accent1_500) else seed()
        val key = Key(source, isDark(context), dynamic)
        if (cache.size > 24) cache.clear()
        return cache.getOrPut(key) {
            val hct = Hct.fromInt(source)
            val scheme = SchemeFidelity(hct, key.dark, 0.0)
            val palette = schemeColors(scheme).let { generated ->
                if (!dynamic && source == DEFAULT_SEED)
                    if (key.dark) LegacyPalette.dark else LegacyPalette.light
                else generated
            }
            palette + (if (key.dark) SemanticPalette.dark else SemanticPalette.light) +
                (R.color.control_ripple to ColorUtils.setAlphaComponent(palette.getValue(R.color.usb_accent), 32))
        }
    }
    @android.annotation.SuppressLint("ResourceAsColor") // Resource IDs are map keys, never color values.
    fun schemeColors(s: DynamicScheme): Map<Int, Int> = mapOf(
        R.color.usb_accent to s.primary, R.color.accent to s.primary,
        R.color.on_usb_accent to s.onPrimary,
        R.color.usb_accent_pressed to ColorUtils.blendARGB(s.primary, s.onPrimary, 0.12f),
        R.color.accent_soft to s.primaryContainer, R.color.usb_accent_soft to s.primaryContainer,
        R.color.on_accent_soft to s.onPrimaryContainer,
        R.color.usb_option_selected to s.primaryContainer, R.color.usb_adb_background to s.surfaceContainerHigh,
        R.color.bg_page to s.surfaceContainer, R.color.bg_card to s.surfaceContainerLowest,
        R.color.usb_surface to s.surfaceContainerLowest,
        R.color.surface_container to s.surfaceContainerHigh, R.color.surface_variant to s.surfaceContainerLow,
        R.color.usb_option_normal to s.surfaceContainerLow,
        R.color.text_primary to s.onSurface, R.color.usb_text_primary to s.onSurface,
        R.color.text_body to s.onSurface, R.color.text_subtitle to s.onSurface,
        R.color.text_secondary to s.onSurfaceVariant, R.color.usb_text_secondary to s.onSurfaceVariant,
        R.color.text_tertiary to ColorUtils.blendARGB(s.onSurfaceVariant, s.surface, 0.18f),
        R.color.usb_text_tertiary to s.outline,
        R.color.outline to s.outlineVariant, R.color.usb_option_border to s.outlineVariant,
        R.color.usb_icon_inactive to s.onSurfaceVariant, R.color.usb_indicator_inactive to s.outline
    )
}

internal fun Context.uiColor(resource: Int): Int = DisplaySettings.colors(this)[resource] ?: getColor(resource)
