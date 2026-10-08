package com.tiger.usbmanager.compatibility

import android.content.Context
import org.json.JSONObject

/** The editable bilingual catalog lives entirely in assets/compatibility_modules.json. */
object CompatibilityConfig {
    @Volatile private var cached: List<CompatibilityModule>? = null

    @Synchronized
    fun load(context: Context): List<CompatibilityModule> {
        cached?.let { return it }
        val modules = parse(context.assets.open("compatibility_modules.json").bufferedReader().use { it.readText() })
        return modules.also { cached = it }
    }

    internal fun parse(json: String): List<CompatibilityModule> {
        val root = JSONObject(json)
        check(root.getInt("schemaVersion") == 1) { "Unsupported compatibility catalog version" }
        val entries = root.getJSONArray("modules")
        val modules = (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            CompatibilityModule(
                packageName = entry.getString("packageName").also { check(it.isNotBlank()) },
                name = entry.text("name"),
                feature = entry.text("feature"),
                briefReason = entry.text("briefReason"),
                detailedReason = entry.text("detailedReason"),
                disableInstructions = entry.text("disableInstructions"),
            )
        }
        check(modules.map { it.packageName }.distinct().size == modules.size) { "Duplicate compatibility package" }
        return modules
    }

    private fun JSONObject.text(key: String): CompatibilityText {
        val value = getJSONObject(key)
        return CompatibilityText(value.getString("zh"), value.getString("en")).also {
            check(it.zh.isNotBlank() && it.en.isNotBlank()) { "Missing bilingual compatibility text: $key" }
        }
    }
}
