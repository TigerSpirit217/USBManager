package com.tiger.usbmanager.ui

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.tiger.usbmanager.R
import com.tiger.usbmanager.compatibility.CompatibilityConfig
import com.tiger.usbmanager.compatibility.CompatibilityModule

/** Fragment arguments preserve the warning across rotation and process recreation. */
class CompatibilityWarningDialog : DialogFragment() {
    private val expandedPackages = mutableSetOf<String>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        expandedPackages.clear()
        expandedPackages.addAll(savedInstanceState?.getStringArrayList(KEY_EXPANDED).orEmpty())
        val context = requireContext()
        val language = resources.configuration.locales[0].language
        val packages = requireArguments().getStringArrayList(KEY_PACKAGES).orEmpty().toSet()
        // Normally already cached by the worker; process restoration may load the small asset.
        val modules = runCatching { CompatibilityConfig.load(context).filter { it.packageName in packages } }
            .getOrElse {
                android.util.Log.w("USBManager", "[COMPAT] Restored warning catalog unavailable", it)
                emptyList()
            }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header(modules.size))
            addView(bodyText(getString(if (modules.isEmpty()) {
                R.string.compatibility_restored_catalog_unavailable
            } else {
                R.string.compatibility_warning_intro
            }), R.color.compatibility_text_secondary).apply {
                setPadding(0, dp(16), 0, dp(4))
            })
            modules.forEach { module ->
                addView(moduleCard(module, language), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) })
            }
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            addView(object : ScrollView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    // Keep long lists and expanded reasons within the safe window in either orientation.
                    val limit = dp((resources.configuration.screenHeightDp * 0.85f).toInt() - 100)
                        .coerceAtLeast(dp(80))
                    val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                        limit
                    } else minOf(limit, MeasureSpec.getSize(heightMeasureSpec))
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
                }
            }.apply {
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(content)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(MaterialButton(context).apply {
                    setText(R.string.dialog_got_it)
                    isAllCaps = false
                    minWidth = dp(112)
                    cornerRadius = dp(15)
                    backgroundTintList = ColorStateList(arrayOf(
                        intArrayOf(android.R.attr.state_pressed), intArrayOf(),
                    ), intArrayOf(
                        context.uiColor(R.color.compatibility_accent_pressed),
                        context.uiColor(R.color.compatibility_accent),
                    ))
                    setTextColor(context.uiColor(R.color.compatibility_on_accent))
                    rippleColor = ColorStateList.valueOf(context.uiColor(R.color.compatibility_ripple))
                    setOnClickListener { dismiss() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) })
        }
        val card = MaterialCardView(context).apply {
            radius = dp(28).toFloat()
            cardElevation = dp(18).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(context.uiColor(R.color.compatibility_surface))
            addView(column)
        }
        return Dialog(context, R.style.Theme_USBManager_Dialog).apply { setContentView(card) }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.55f }
            setGravity(Gravity.CENTER)
            setLayout(dp(minOf(560, (resources.configuration.screenWidthDp - 36).coerceAtLeast(1))),
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun header(count: Int) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_compatibility_warning)
            imageTintList = ColorStateList.valueOf(context.uiColor(R.color.compatibility_accent))
            background = context.roundedBackground(R.color.compatibility_icon_background, 14)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(bodyText(getString(R.string.compatibility_warning_title)).apply {
                textSize = 21f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                ViewCompat.setAccessibilityHeading(this, true)
            })
            if (count > 0) addView(bodyText(getString(R.string.compatibility_warning_count, count),
                R.color.compatibility_text_secondary).apply {
                textSize = 12f
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(14)
        })
    }

    private fun moduleCard(module: CompatibilityModule, language: String): MaterialCardView {
        val context = requireContext()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(4))
            addView(bodyText(module.name.forLanguage(language)).apply {
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                ViewCompat.setAccessibilityHeading(this, true)
            })
            addView(bodyText(module.packageName, R.color.compatibility_text_secondary).apply {
                textSize = 12f
                setPadding(0, dp(3), 0, dp(10))
            })
            addView(bodyText(getString(R.string.compatibility_feature, module.feature.forLanguage(language)),
                R.color.compatibility_text_secondary).apply { textSize = 13f })
            addView(bodyText(getString(R.string.compatibility_reason, module.briefReason.forLanguage(language)))
                .apply { setPadding(0, dp(6), 0, dp(12)) })
            addView(View(context).apply {
                setBackgroundColor(context.uiColor(R.color.compatibility_outline))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            addView(bodyText(getString(R.string.compatibility_disable, module.disableInstructions.forLanguage(language)))
                .apply { setPadding(0, dp(12), 0, 0) })
            val detail = bodyText(module.detailedReason.forLanguage(language), R.color.compatibility_text_secondary)
                .apply {
                    visibility = if (module.packageName in expandedPackages) View.VISIBLE else View.GONE
                    setPadding(0, 0, 0, dp(12))
                }
            addView(TextView(context).apply {
                fun updateLabel() = setText(if (module.packageName in expandedPackages) {
                    R.string.compatibility_hide_details
                } else R.string.compatibility_show_details)
                updateLabel()
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                setTextColor(context.uiColor(R.color.compatibility_accent))
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                background = RippleDrawable(ColorStateList.valueOf(context.uiColor(R.color.compatibility_ripple)),
                    null, context.roundedBackground(R.color.compatibility_module_surface, 8))
                setOnClickListener {
                    val expanded = expandedPackages.add(module.packageName)
                    if (!expanded) expandedPackages.remove(module.packageName)
                    if (UiMotion.enabled()) android.transition.TransitionManager.beginDelayedTransition(
                        detail.parent as ViewGroup, android.transition.AutoTransition().setDuration(220))
                    detail.visibility = if (expanded) View.VISIBLE else View.GONE
                    updateLabel()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(detail)
        }
        return context.surfaceCard().apply {
            setCardBackgroundColor(context.uiColor(R.color.compatibility_module_surface))
            strokeColor = context.uiColor(R.color.compatibility_outline)
            addView(column)
        }
    }

    private fun bodyText(value: String, color: Int = R.color.compatibility_text_primary) = TextView(requireContext()).apply {
        text = value
        textSize = 14f
        setTextColor(context.uiColor(color))
        setLineSpacing(0f, 1.18f)
        setTextIsSelectable(true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_EXPANDED, ArrayList(expandedPackages))
    }

    override fun onDetach() {
        val main = activity as? MainActivity
        super.onDetach()
        main?.onCompatibilityWarningDismissed()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "compatibility_warning"
        private const val KEY_PACKAGES = "packages"
        private const val KEY_EXPANDED = "expanded_packages"

        fun forPackages(packages: List<String>) = CompatibilityWarningDialog().apply {
            arguments = Bundle().apply { putStringArrayList(KEY_PACKAGES, ArrayList(packages)) }
        }
    }
}
