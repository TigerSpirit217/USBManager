package com.tiger.usbmanager.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tiger.usbmanager.ModuleSettings
import com.tiger.usbmanager.R

class GameDndSettingsActivity : LocalizedActivity() {
    private lateinit var packagesSummary: TextView
    private lateinit var advancedSettings: LinearLayout
    private lateinit var behaviorGroup: RadioGroup
    private var chargingId = View.NO_ID
    private var defaultId = View.NO_ID
    private var restoringBehavior = false
    private var riskDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModuleSettings.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(uiColor(R.color.bg_page))
            applySystemBarPadding(includeHorizontal = true)
            addView(toolbar(getString(R.string.game_dnd_title), back = { finish() }).apply {
                applySystemBarPadding(includeTop = true)
            })
        }
        advancedSettings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (ModuleSettings.gameDndEnabled()) View.VISIBLE else View.GONE
            addView(sectionLabel(getString(R.string.game_dnd_action_title)))
            addView(behaviorCard())
            addView(sectionLabel(getString(R.string.game_dnd_apps_title)))
            addView(applicationsCard())
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(28))
            applySystemBarPadding(includeBottom = true)
            val summary = LinearLayout(this@GameDndSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(surfaceCard().apply {
                    addView(bodyText(R.string.game_dnd_description).apply {
                        setPadding(dp(18), dp(17), dp(18), dp(17))
                    })
                })
                addView(sectionLabel(getString(R.string.settings_module_settings)))
                addView(enableCard())
            }
            addView(adaptiveColumns(summary, advancedSettings))
        }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(column) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun enableCard(): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(this@GameDndSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(12))
            addView(TextView(context).apply {
                setText(R.string.game_dnd_enable)
                textSize = 15f
                setTextColor(uiColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(SwitchMaterial(context).apply {
                useUsbManagerColors()
                isChecked = ModuleSettings.gameDndEnabled()
                setOnCheckedChangeListener { _, enabled ->
                    ModuleSettings.prefs().edit { putBoolean(ModuleSettings.KEY_GAME_DND_ENABLED, enabled) }
                    if (!enabled) riskDialog?.cancel()
                    if (UiMotion.enabled()) android.transition.TransitionManager.beginDelayedTransition(
                        advancedSettings.parent as ViewGroup, android.transition.AutoTransition().setDuration(220))
                    advancedSettings.visibility = if (enabled) View.VISIBLE else View.GONE
                }
            })
        })
    }

    private fun behaviorCard(): MaterialCardView {
        chargingId = View.generateViewId()
        defaultId = View.generateViewId()
        behaviorGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(behaviorOption(chargingId, R.string.game_dnd_charging))
            addView(divider())
            addView(behaviorOption(defaultId, R.string.game_dnd_default))
        }
        restoreBehaviorSelection()
        behaviorGroup.setOnCheckedChangeListener { _, id ->
            if (restoringBehavior) return@setOnCheckedChangeListener
            if (id == defaultId) showDefaultRisk()
            else if (id == chargingId) {
                ModuleSettings.prefs().edit { putString(ModuleSettings.KEY_GAME_DND_ACTION, "charging") }
            }
        }
        return surfaceCard().apply { addView(behaviorGroup) }
    }

    private fun behaviorOption(viewId: Int, res: Int) = RadioButton(this).apply {
        id = viewId
        setText(res)
        textSize = 15f
        setTextColor(uiColor(R.color.text_primary))
        buttonTintList = context.controlColors()
        minHeight = dp(52)
    }

    private fun restoreBehaviorSelection() {
        // RadioGroup.check() can report the old ID while unchecking its button.
        // Ignore all callbacks during restoration, including intermediate IDs.
        restoringBehavior = true
        try { behaviorGroup.check(if (ModuleSettings.gameDndUsesDefault()) defaultId else chargingId) }
        finally { restoringBehavior = false }
    }

    private fun showDefaultRisk() {
        if (riskDialog != null || ModuleSettings.gameDndUsesDefault()) return
        var confirmed = false
        riskDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.game_dnd_risk_title)
            .setMessage(R.string.game_dnd_default_risk)
            .setPositiveButton(R.string.chooser_confirm) { _, _ ->
                confirmed = true
                ModuleSettings.prefs().edit { putString(ModuleSettings.KEY_GAME_DND_ACTION, "default") }
            }
            .setNegativeButton(R.string.chooser_cancel, null)
            .create().apply {
                // Cancel, Back and outside taps all use this single rollback path.
                setOnDismissListener {
                    riskDialog = null
                    if (!confirmed) restoreBehaviorSelection()
                }
            }
        riskDialog?.apply {
            show()
            window?.fitLandscapeDialog()
            findViewById<TextView>(android.R.id.message)?.setTextColor(uiColor(R.color.game_dnd_warning))
        }
    }

    private fun applicationsCard(): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(this@GameDndSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true
                isFocusable = true
                val selectable = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
                foreground = getDrawable(selectable.resourceId)
                setOnClickListener { startActivity(Intent(this@GameDndSettingsActivity, GameDndAppPickerActivity::class.java)) }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        setText(R.string.game_dnd_picker_title)
                        textSize = 15f
                        setTextColor(uiColor(R.color.text_primary))
                    })
                    packagesSummary = TextView(context).apply {
                        textSize = 12f
                        setTextColor(uiColor(R.color.usb_text_secondary))
                        setPadding(0, dp(3), 0, 0)
                    }
                    addView(packagesSummary)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_arrow_forward)
                    imageTintList = android.content.res.ColorStateList.valueOf(uiColor(R.color.usb_accent))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
            })
            addView(divider())
            addView(bodyText(R.string.game_dnd_apps_hint).apply {
                textSize = 12f
                setTextColor(uiColor(R.color.text_secondary))
                setPadding(dp(16), dp(12), dp(16), dp(14))
            })
        })
    }

    private fun bodyText(res: Int) = TextView(this).apply {
        setText(res)
        textSize = 12f
        setTextColor(uiColor(R.color.text_secondary))
        setLineSpacing(0f, 1.18f)
    }

    override fun onResume() {
        super.onResume()
        packagesSummary.text = getString(R.string.game_dnd_apps_count, ModuleSettings.gameDndPackages().size)
    }

    override fun onDestroy() {
        riskDialog?.dismiss()
        super.onDestroy()
    }
}
