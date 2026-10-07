package com.tiger.usbmanager.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameDndPolicyTest {
    private val games = setOf("com.example.game", "com.example.video")

    private fun decide(enabled: Boolean = true, foreground: String? = "com.example.game",
                       useDefault: Boolean = false, packages: Set<String> = games,
                       mode: UsbMode = UsbMode.MTP, adb: Boolean = true) =
        GameDndPolicy.configuration(enabled, packages, foreground, useDefault, mode, adb)

    @Test fun disabledFeatureDoesNotSuppressEvenForSelectedForegroundApp() {
        assertNull(decide(enabled = false))
    }

    @Test fun selectedBackgroundAppDoesNotSuppressOtherForegroundApps() {
        assertNull(decide(foreground = "com.example.other"))
    }

    @Test fun unknownForegroundAndEmptySelectionKeepNormalBehavior() {
        assertNull(decide(foreground = null))
        assertNull(decide(packages = emptySet()))
    }

    @Test fun chargingPolicyDisablesDataAndAdbDespiteUnsafeDefaults() {
        UsbMode.entries.forEach { default ->
            assertEquals(GameDndPolicy.Configuration(UsbMode.CHARGING, false), decide(mode = default, adb = true))
        }
    }

    @Test fun explicitDefaultPolicyPreservesBothSelectedModeAndAdb() {
        UsbMode.entries.forEach { default ->
            listOf(false, true).forEach { adb ->
                assertEquals(GameDndPolicy.Configuration(default, adb), decide(useDefault = true, mode = default, adb = adb))
            }
        }
    }

    @Test fun allSelectedAppsMatchButSimilarPackageNamesDoNot() {
        assertEquals(GameDndPolicy.Configuration(UsbMode.CHARGING, false), decide(foreground = "com.example.video"))
        assertNull(decide(foreground = "com.example.game.extra"))
    }
}
