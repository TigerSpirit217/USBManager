package com.tiger.usbmanager

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/** Chinese lives in values, so choose one supported language before Android's fallback. */
internal fun selectDisplayLocale(locales: List<Locale>): Locale =
    locales.firstOrNull { it.language == "zh" || it.language == "en" } ?: Locale.SIMPLIFIED_CHINESE

internal fun Context.withDisplayLanguage(configuration: Configuration = resources.configuration): Context {
    val locales = configuration.locales
    val selected = selectDisplayLocale((0 until locales.size()).map { locales[it] })
    // Override only locales. Orientation, density and other window configuration must
    // continue to update, especially for the chooser's onConfigurationChanged path.
    val displayConfiguration = Configuration().apply { setLocales(LocaleList(selected)) }
    return createConfigurationContext(displayConfiguration)
}
