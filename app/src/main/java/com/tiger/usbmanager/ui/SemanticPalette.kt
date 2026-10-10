package com.tiger.usbmanager.ui

import com.tiger.usbmanager.R

/** Fixed semantic colors resolve from the chosen mode, independent of stale night resource caches. */
internal object SemanticPalette {
    val light = mapOf(
        R.color.banner_active_bg to 0xFFE8F5E9.toInt(),
        R.color.banner_active_text to 0xFF2E7D32.toInt(),
        R.color.banner_inactive_bg to 0xFFFFEBEE.toInt(),
        R.color.banner_inactive_text to 0xFFC62828.toInt(),
        R.color.banner_loading_bg to 0xFFFFF8E1.toInt(),
        R.color.banner_loading_text to 0xFF8D6E63.toInt(),
        R.color.banner_unknown_bg to 0xFFE3F2FD.toInt(),
        R.color.banner_unknown_text to 0xFF1565C0.toInt(),
        R.color.black to 0xFF000000.toInt(),
        R.color.compatibility_accent to 0xFF805D00.toInt(),
        R.color.compatibility_accent_pressed to 0xFF694B00.toInt(),
        R.color.compatibility_icon_background to 0xFFFFE6A1.toInt(),
        R.color.compatibility_module_surface to 0xFFFFFBEA.toInt(),
        R.color.compatibility_on_accent to 0xFFFFFFFF.toInt(),
        R.color.compatibility_outline to 0xFFE9D79A.toInt(),
        R.color.compatibility_ripple to 0x26805D00.toInt(),
        R.color.compatibility_surface to 0xFFFFF4CC.toInt(),
        R.color.compatibility_text_primary to 0xFF3D310E.toInt(),
        R.color.compatibility_text_secondary to 0xFF75653C.toInt(),
        R.color.game_dnd_warning to 0xFFC62828.toInt(),
        R.color.log_text to 0xFF222222.toInt(),
        R.color.scrim to 0x85000000.toInt()
    )
    val dark = mapOf(
        R.color.banner_active_bg to 0xFF1B3A1B.toInt(),
        R.color.banner_active_text to 0xFF81C784.toInt(),
        R.color.banner_inactive_bg to 0xFF3A1B1B.toInt(),
        R.color.banner_inactive_text to 0xFFEF9A9A.toInt(),
        R.color.banner_loading_bg to 0xFF3A2F1B.toInt(),
        R.color.banner_loading_text to 0xFFBCAAA4.toInt(),
        R.color.banner_unknown_bg to 0xFF1B2A3A.toInt(),
        R.color.banner_unknown_text to 0xFF90CAF9.toInt(),
        R.color.black to 0xFFFFFFFF.toInt(),
        R.color.compatibility_accent to 0xFFF1CB67.toInt(),
        R.color.compatibility_accent_pressed to 0xFFFFE09A.toInt(),
        R.color.compatibility_icon_background to 0xFF56431E.toInt(),
        R.color.compatibility_module_surface to 0xFF40351C.toInt(),
        R.color.compatibility_on_accent to 0xFF352600.toInt(),
        R.color.compatibility_outline to 0xFF66542A.toInt(),
        R.color.compatibility_ripple to 0x33F1CB67.toInt(),
        R.color.compatibility_surface to 0xFF332B16.toInt(),
        R.color.compatibility_text_primary to 0xFFFFF3C4.toInt(),
        R.color.compatibility_text_secondary to 0xFFD4C394.toInt(),
        R.color.game_dnd_warning to 0xFFFF8A80.toInt(),
        R.color.log_text to 0xFFCCCCCC.toInt(),
        R.color.scrim to 0xA6000000.toInt()
    )
}
