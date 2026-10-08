package com.tiger.usbmanager.compatibility

data class CompatibilityText(val zh: String, val en: String) {
    fun forLanguage(language: String): String = if (language == "en") en else zh
}

data class CompatibilityModule(
    val packageName: String,
    val name: CompatibilityText,
    val feature: CompatibilityText,
    val briefReason: CompatibilityText,
    val detailedReason: CompatibilityText,
    val disableInstructions: CompatibilityText,
)

/** A missing snapshot means the first successful scan, including all current apps. */
object CompatibilityPolicy {
    fun newConflicts(
        currentPackages: Set<String>,
        previousPackages: Set<String>?,
        modules: List<CompatibilityModule>,
    ): List<CompatibilityModule> {
        val added = if (previousPackages == null) currentPackages else currentPackages - previousPackages
        return modules.filter { it.packageName in added }
    }
}
