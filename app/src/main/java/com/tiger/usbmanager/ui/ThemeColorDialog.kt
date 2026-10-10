package com.tiger.usbmanager.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.tiger.usbmanager.R

/** HSV and hex edit one seed; previews show the generated palette before saving. */
internal class ThemeColorDialog(private val activity: Activity, private val save: (Int) -> Unit) {
    private val hsv = FloatArray(3).also { Color.colorToHSV(DisplaySettings.seed(), it) }
    private var binding = false
    private val sliders = mutableListOf<Slider>()
    private val values = mutableListOf<TextView>()

    fun show() = with(activity) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        val swatch = View(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val input = EditText(this).apply {
            isSingleLine = true; textSize = 17f; typeface = android.graphics.Typeface.MONOSPACE
            hint = "#RRGGBB"; setText(DisplaySettings.hex(DisplaySettings.seed()))
            setTextColor(uiColor(R.color.text_primary))
            filters = arrayOf(InputFilter.LengthFilter(7))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            contentDescription = getString(R.string.theme_hex)
            backgroundTintList = ColorStateList.valueOf(uiColor(R.color.usb_accent))
        }
        column.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(swatch, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(16) })
            addView(input, LinearLayout.LayoutParams(0, dp(56), 1f))
        })
        val primary = TextView(this).apply {
            text = getString(R.string.palette_preview_primary); gravity = Gravity.CENTER; textSize = 14f
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val container = TextView(this).apply {
            text = getString(R.string.palette_preview_container); gravity = Gravity.CENTER; textSize = 14f
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        column.addView(LinearLayout(this).apply {
            addView(primary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(container, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, verticalMargins(top = dp(12)))

        fun update(inputChanged: Boolean = false) {
            val selected = Color.HSVToColor(hsv)
            swatch.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(selected) }
            val colors = DisplaySettings.colors(activity, selected)
            primary.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(colors.getValue(R.color.usb_accent)) }
            primary.setTextColor(colors.getValue(R.color.on_usb_accent))
            container.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(colors.getValue(R.color.accent_soft)) }
            container.setTextColor(colors.getValue(R.color.on_accent_soft))
            binding = true
            if (!inputChanged) { input.setText(DisplaySettings.hex(selected)); input.error = null }
            val percentages = floatArrayOf(hsv[0], hsv[1] * 100f, hsv[2] * 100f)
            sliders.forEachIndexed { i, slider ->
                slider.value = percentages[i].coerceIn(slider.valueFrom, slider.valueTo)
                values[i].text = if (i == 0) "${percentages[i].toInt()}°" else "${percentages[i].toInt()}%"
            }
            binding = false
        }
        listOf(R.string.theme_hue, R.string.theme_saturation, R.string.theme_value).forEachIndexed { i, label ->
            val valueLabel = TextView(this).apply { textSize = 12f; setTextColor(uiColor(R.color.text_secondary)) }
            values.add(valueLabel)
            column.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply { setText(label); textSize = 13f; setTextColor(uiColor(R.color.text_primary)) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(valueLabel)
            }, verticalMargins(top = dp(10), bottom = 0))
            val slider = Slider(this).apply {
                valueFrom = 0f; valueTo = if (i == 0) 360f else 100f
                thumbTintList = ColorStateList.valueOf(uiColor(R.color.usb_accent))
                trackActiveTintList = thumbTintList
                trackInactiveTintList = ColorStateList.valueOf(uiColor(R.color.accent_soft))
                contentDescription = getString(label)
                addOnChangeListener { _, value, fromUser ->
                    if (fromUser && !binding) { hsv[i] = if (i == 0) value else value / 100f; update() }
                }
            }
            sliders.add(slider)
            column.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        }
        column.addView(TextView(this).apply {
            setText(R.string.theme_seed_hint); textSize = 12f; setTextColor(uiColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, dp(10))
        })
        val presetSeeds = intArrayOf(DisplaySettings.DEFAULT_SEED, 0xFF25764F.toInt(), 0xFF7454B5.toInt(),
            0xFFA85519.toInt(), 0xFFB4426B.toInt(), 0xFF007B83.toInt())
        val labels = intArrayOf(R.string.theme_blue, R.string.theme_green, R.string.theme_purple, R.string.theme_orange, R.string.theme_rose, R.string.theme_teal)
        column.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            presetSeeds.forEachIndexed { i, seed ->
                addView(android.widget.FrameLayout(activity).apply {
                    addView(View(activity).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(seed) }
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, android.widget.FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
                    isClickable = true; isFocusable = true; contentDescription = getString(labels[i]); clickFeedback(24)
                    setOnClickListener { Color.colorToHSV(seed, hsv); update(); UiMotion.selected(swatch) }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
        })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (binding) return
                val hex = s.toString()
                if (hex.matches(Regex("#[0-9a-fA-F]{6}"))) {
                    input.error = null; Color.colorToHSV(hex.toColorInt(), hsv); update(true)
                }
            }
        })
        update()
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.display_theme_color)
            .setView(ScrollView(this).apply { addView(column) })
            .setNegativeButton(R.string.dialog_cancel, null).setPositiveButton(R.string.chooser_confirm, null).create()
        dialog.setOnShowListener {
            dialog.applyUsbDialogColors()
            UiMotion.enter(column)
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (!input.text.toString().matches(Regex("#[0-9a-fA-F]{6}"))) { input.error = getString(R.string.theme_hex_invalid); return@setOnClickListener }
                save(Color.HSVToColor(hsv)); dialog.dismiss()
            }
        }
        dialog.show()
    }
}
