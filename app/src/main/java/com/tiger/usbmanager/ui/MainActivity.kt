package com.tiger.usbmanager.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.activity.addCallback
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tiger.usbmanager.ModuleActivationCheck
import com.tiger.usbmanager.ModuleSettings
import com.tiger.usbmanager.R
import com.tiger.usbmanager.bridge.InstalledPackageCatalog
import com.tiger.usbmanager.compatibility.CompatibilityConfig
import com.tiger.usbmanager.compatibility.CompatibilityPolicy
import com.tiger.usbmanager.compatibility.CompatibilitySnapshotStore
import com.tiger.usbmanager.policy.UsbMode
import com.tiger.usbmanager.withDisplayLanguage
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : FragmentActivity() {
    private enum class Page(val title: Int, val icon: Int) {
        HOME(R.string.nav_home, R.drawable.ic_home),
        STATUS(R.string.nav_status, R.drawable.ic_status),
        SETTINGS(R.string.nav_settings, R.drawable.ic_settings),
    }
    private var selectedPage = Page.HOME
    private val pageScroll = IntArray(Page.entries.size)
    private var pageScrollView: ScrollView? = null
    private var contentHost: FrameLayout? = null
    private var toolbarHost: LinearLayout? = null
    private var navigation: AppNavigationBar? = null
    private var appearanceChanging = false
    private val usbStatusPage by lazy { UsbStatusPage(this) }
    private lateinit var activationStatusContainer: LinearLayout
    private var defaultConfigSummaryView: TextView? = null
    private var gameDndSummaryView: TextView? = null
    private var hasResumed = false
    private var mainScreenVisible = false
    private var mainResumed = false
    private var compatibilityCheckPending = true
    private var compatibilityGeneration = 0
    private var compatibilityTask: Future<*>? = null
    private val compatibilityHandler = Handler(Looper.getMainLooper())
    private val compatibilityWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "usb-compatibility-check").apply { isDaemon = true }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(DisplaySettings.wrapContext(newBase.withDisplayLanguage()))
        applyOverrideConfiguration(DisplaySettings.nightConfiguration())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DisplaySettings.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ModuleSettings.init(this)
        appearanceChanging = savedInstanceState?.getBoolean("appearance_changing", false) ?: false
        selectedPage = Page.entries.getOrElse(savedInstanceState?.getInt("page", 0) ?: 0) { Page.HOME }
        savedInstanceState?.getIntArray("page_scroll")?.takeIf { it.size == pageScroll.size }?.copyInto(pageScroll)
        if (ModuleSettings.isFirstLaunchDone()) showConfigManager() else showIntro()
        onBackPressedDispatcher.addCallback(this) {
            when {
                !mainScreenVisible && ModuleSettings.isFirstLaunchDone() -> showConfigManager()
                mainScreenVisible && selectedPage != Page.HOME -> selectPage(Page.HOME)
                else -> finish()
            }
        }
    }

    private fun showIntro() {
        savePageScroll()
        mainScreenVisible = false
        cancelCompatibilityCheck()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
            applySystemBarPadding(includeTop = true, includeBottom = true)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 32f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(uiColor(R.color.text_primary))
            })
            val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
                ?: getString(R.string.version_unknown)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.intro_module_info, versionName)
                textSize = 14f
                setTextColor(uiColor(R.color.text_tertiary))
                setPadding(0, dp(4), 0, dp(12))
            })
            addView(infoCard(
                getString(R.string.intro_section_features),
                listOf(R.string.intro_feature_1, R.string.intro_feature_2, R.string.intro_feature_3, R.string.intro_feature_4,
                    R.string.intro_feature_5, R.string.intro_feature_6, R.string.intro_feature_7),
            ), verticalMargins(top = dp(8)))
            addView(infoCard(
                getString(R.string.intro_section_usage),
                listOf(R.string.intro_usage_1, R.string.intro_usage_2, R.string.intro_usage_3,
                    R.string.intro_usage_4, R.string.intro_usage_5, R.string.intro_usage_6),
            ), verticalMargins(top = dp(4)))
            addView(primaryButton(getString(R.string.intro_start)) {
                ModuleSettings.markFirstLaunchDone()
                recreate()
            }, verticalMargins(top = dp(12), bottom = 0))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(uiColor(R.color.bg_page))
            isFillViewport = true
            addView(column)
        })
    }

    private fun infoCard(title: String, lines: List<Int>): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(uiColor(R.color.on_accent_soft))
                setPadding(0, 0, 0, dp(8))
            })
            lines.forEach { res -> addView(TextView(this@MainActivity).apply {
                setText(res)
                textSize = 14f
                setTextColor(uiColor(R.color.text_body))
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(5), 0, dp(5))
            }) }
        })
    }

    private fun showConfigManager() {
        mainScreenVisible = true
        val floating = DisplaySettings.floating()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(uiColor(R.color.bg_page))
            applySystemBarPadding(includeHorizontal = true)
        }
        toolbarHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            applySystemBarPadding(includeTop = true)
        }
        root.addView(toolbarHost)
        contentHost = PageSwipeHost(this) { physicalStep ->
            val step = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) -physicalStep else physicalStep
            Page.entries.getOrNull(selectedPage.ordinal + step)?.let { selectPage(it) }
        }
        navigation = AppNavigationBar(this, floating, Page.entries.map { it.title to it.icon }, selectedPage.ordinal) {
            selectPage(Page.entries[it])
        }
        if (floating) {
            root.addView(FrameLayout(this).apply {
                clipChildren = false
                addView(contentHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(FrameLayout(this@MainActivity).apply {
                    clipChildren = false
                    setPadding(dp(18), 0, dp(18), dp(12))
                    applySystemBarPadding(includeBottom = true)
                    val barWidth = minOf(dp(320), dp(resources.configuration.screenWidthDp - 36))
                    addView(navigation, FrameLayout.LayoutParams(barWidth, dp(64), Gravity.CENTER_HORIZONTAL))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            root.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(uiColor(R.color.bg_card))
                applySystemBarPadding(includeBottom = true)
                addView(View(this@MainActivity).apply { setBackgroundColor(uiColor(R.color.outline)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                addView(navigation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)))
            })
        }
        setContentView(root)
        populatePage(false)
        if (appearanceChanging) { UiMotion.enter(root, 0); appearanceChanging = false }
    }

    private fun populatePage(animated: Boolean, direction: Int = 1) {
        defaultConfigSummaryView = null
        gameDndSummaryView = null
        toolbarHost?.apply {
            removeAllViews()
            addView(toolbar(getString(if (selectedPage == Page.HOME) R.string.app_name else selectedPage.title),
                action = if (selectedPage == Page.HOME) getString(R.string.settings_view_intro) to { showIntro() } else null).apply {
                    setPadding(dp(18), paddingTop, dp(18), paddingBottom)
                })
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(24))
            when (selectedPage) {
                Page.HOME -> {
                    addView(sectionLabel(getString(R.string.settings_status_section)))
                    activationStatusContainer = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                    addView(activationStatusContainer)
                    addView(sectionLabel(getString(R.string.settings_module_settings)))
                    addView(settingsCard())
                    addView(sectionLabel(getString(R.string.auth_section_title)))
                    addView(authenticationCard())
                }
                Page.STATUS -> addView(usbStatusPage.content())
                Page.SETTINGS -> addView(AppearancePage(this@MainActivity,
                    themeChanged = { changeAppearance() }, barChanged = {
                        savePageScroll(); showConfigManager(); navigation?.let { UiMotion.enter(it, 8) }
                    }).content())
            }
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
            if (selectedPage != Page.HOME) addView(developerFooter())
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            if (DisplaySettings.floating()) {
                setPadding(0, 0, 0, dp(88))
                applySystemBarPadding(includeBottom = true)
            }
            addView(column)
        }
        val old = pageScrollView
        pageScrollView = scroll
        val host = contentHost ?: return
        for (i in host.childCount - 1 downTo 0) {
            val child = host.getChildAt(i)
            child.animate().cancel()
            if (child != old) host.removeView(child)
        }
        host.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val page = selectedPage
        scroll.post { if (pageScrollView === scroll) scroll.scrollTo(0, pageScroll[page.ordinal]) }
        if (animated && old != null && old.parent === host && UiMotion.enabled()) {
            scroll.alpha = 0f
            scroll.translationX = dp(20).toFloat() * direction
            scroll.animate().alpha(1f).translationX(0f).setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            old.isEnabled = false
            old.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            old.animate().alpha(0f).translationX(-dp(12).toFloat() * direction).setDuration(180)
                .withEndAction { host.removeView(old) }.start()
        } else if (old?.parent === host) host.removeView(old)
        if (selectedPage == Page.HOME) refreshActivationStatus()
        checkCompatibilityOnEntry()
    }

    private fun savePageScroll() { pageScrollView?.let { pageScroll[selectedPage.ordinal] = it.scrollY } }

    private fun selectPage(page: Page) {
        if (selectedPage == page) return
        savePageScroll()
        val direction = if (page.ordinal > selectedPage.ordinal) 1 else -1
        selectedPage = page
        navigation?.select(page.ordinal, true)
        populatePage(true, direction)
    }

    private fun changeAppearance() {
        savePageScroll()
        appearanceChanging = true
        recreate()
    }

    private fun checkCompatibilityOnEntry() {
        if (!mainScreenVisible || !mainResumed || !compatibilityCheckPending ||
            compatibilityTask != null || isFinishing || isDestroyed || supportFragmentManager.isStateSaved ||
            supportFragmentManager.findFragmentByTag(CompatibilityWarningDialog.TAG) != null) return
        compatibilityCheckPending = false
        val generation = ++compatibilityGeneration
        val context = applicationContext
        compatibilityTask = compatibilityWorker.submit {
            val result = runCatching {
                // A successful system catalog response is the injection check.
                // No app-side enumeration or stale activation property is used.
                val packages = InstalledPackageCatalog.loadPackageNames(context)
                val modules = CompatibilityConfig.load(context)
                val previous = CompatibilitySnapshotStore(context).previousPackages()
                packages to CompatibilityPolicy.newConflicts(packages, previous, modules)
            }
            if (Thread.currentThread().isInterrupted) return@submit
            compatibilityHandler.post {
                if (generation != compatibilityGeneration || isFinishing || isDestroyed) return@post
                compatibilityTask = null
                if (!mainResumed || !mainScreenVisible || supportFragmentManager.isStateSaved) {
                    compatibilityCheckPending = true
                    return@post
                }
                result.onSuccess { (packages, conflicts) ->
                    runCatching {
                        if (conflicts.isNotEmpty()) {
                            CompatibilityWarningDialog.forPackages(conflicts.map { it.packageName })
                                .showNow(supportFragmentManager, CompatibilityWarningDialog.TAG)
                        }
                        // Record every successful scan, even with no conflicts. The
                        // warning must be shown before an affected app is recorded.
                        CompatibilitySnapshotStore(context).record(packages)
                    }.onFailure {
                        android.util.Log.w("USBManager", "[COMPAT] Could not show or record compatibility scan", it)
                    }
                }.onFailure {
                    android.util.Log.w("USBManager", "[COMPAT] System package scan unavailable; previous snapshot retained", it)
                }
            }
        }
    }

    internal fun onCompatibilityWarningDismissed() {
        compatibilityHandler.post { checkCompatibilityOnEntry() }
    }

    private fun cancelCompatibilityCheck() {
        compatibilityGeneration += 1
        compatibilityTask?.cancel(true)
        compatibilityTask = null
        compatibilityHandler.removeCallbacksAndMessages(null)
    }

    private fun refreshActivationStatus() {
        val previousHeight = activationStatusContainer.getChildAt(0)?.height?.takeIf { it > 0 }
        activationStatusContainer.removeAllViews()
        val loadingCard = statusCard(
            R.color.banner_loading_bg,
            R.color.banner_loading_text,
            getString(R.string.activate_checking),
            null,
            loading = true,
        )
        if (previousHeight != null) loadingCard.minimumHeight = previousHeight
        activationStatusContainer.addView(loadingCard)
        val handler = Handler(Looper.getMainLooper())
        Thread {
            val status = runCatching { ModuleActivationCheck.check(this) }.getOrElse {
                android.util.Log.w("USBManager", "Activation check failed", it)
                ModuleActivationCheck.Status.Unknown(getString(R.string.activate_check_error))
            }
            handler.post { renderActivationStatus(status) }
        }.apply { name = "usb-activation-check"; isDaemon = true }.start()
    }

    private fun renderActivationStatus(status: ModuleActivationCheck.Status) {
        if (!::activationStatusContainer.isInitialized || selectedPage != Page.HOME || isDestroyed) return
        activationStatusContainer.removeAllViews()
        val view = when (status) {
            is ModuleActivationCheck.Status.Active -> statusCard(
                R.color.banner_active_bg, R.color.banner_active_text,
                getString(R.string.activate_state_active),
                getString(R.string.activate_body,
                    getString(if (status.hasUsbDeviceManagerHook) R.string.activate_hook_ok else R.string.activate_hook_fail),
                    getString(if (status.hasAdbHook) R.string.activate_hook_ok else R.string.activate_hook_fail), status.packageName),
                actions = listOf(getString(R.string.action_recheck) to { refreshActivationStatus() }),
            )
            is ModuleActivationCheck.Status.Inactive -> statusCard(
                R.color.banner_inactive_bg, R.color.banner_inactive_text,
                getString(R.string.activate_state_inactive), status.reason, actions = statusActions(),
            )
            is ModuleActivationCheck.Status.Unknown -> statusCard(
                R.color.banner_unknown_bg, R.color.banner_unknown_text,
                getString(R.string.activate_state_unknown), status.note, actions = statusActions(),
            )
        }
        activationStatusContainer.addView(view)
    }

    private fun statusActions() = listOf(
        getString(R.string.action_activation_guide) to { showActivationGuide() },
        getString(R.string.action_recheck) to { refreshActivationStatus() },
    )

    private fun statusCard(
        background: Int,
        foreground: Int,
        title: String,
        body: String?,
        loading: Boolean = false,
        actions: List<Pair<String, () -> Unit>> = emptyList(),
    ): MaterialCardView = MaterialCardView(this).apply {
        setCardBackgroundColor(uiColor(background))
        radius = dp(20).toFloat()
        cardElevation = 0f
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(14))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (loading) addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleSmall),
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(uiColor(foreground))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            if (body != null) addView(TextView(this@MainActivity).apply {
                text = body
                textSize = 13f
                setTextColor(uiColor(R.color.text_secondary))
                setPadding(0, dp(7), 0, 0)
            })
            if (actions.isNotEmpty()) addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(10), 0, 0)
                actions.forEach { action -> addView(outlinedButton(action.first, action.second)) }
            })
        })
    }

    private fun settingsCard(): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(valueRow(
                getString(R.string.settings_default_usb_config),
                defaultConfigSummary(),
                onValueBound = { defaultConfigSummaryView = it },
            ) { startActivity(Intent(this@MainActivity, DefaultUsbConfigActivity::class.java)) })
            addView(divider())
            addView(toggleRow(getString(R.string.settings_disconnect_auto_off), ModuleSettings.disconnectAutoOffAdb()) {
                ModuleSettings.prefs().edit().putBoolean(ModuleSettings.KEY_DISCONNECT_AUTO_OFF_ADB, it).apply()
            })
            addView(divider())
            addView(toggleRow(getString(R.string.settings_chooser_while_locked), ModuleSettings.chooserWhileLocked()) {
                ModuleSettings.prefs().edit().putBoolean(ModuleSettings.KEY_CHOOSER_WHILE_LOCKED, it).apply()
            })
            addView(divider())
            addView(valueRow(getString(R.string.game_dnd_title), gameDndSummary(), onValueBound = { gameDndSummaryView = it }) {
                startActivity(Intent(this@MainActivity, GameDndSettingsActivity::class.java))
            })
            addView(divider())
            addView(valueRow(getString(R.string.action_get_logs), getString(R.string.settings_logs_description)) { showHowToGetLogs() })
        })
    }

    private fun authenticationCard(): MaterialCardView = surfaceCard().apply {
        addView(valueRow(getString(R.string.auth_entry_title), getString(R.string.auth_entry_value)) {
            startActivity(Intent(this@MainActivity, UsbAuthenticationActivity::class.java))
        })
    }

    private fun defaultConfigSummary(): String {
        val mode = getString(UsbMode.fromWire(ModuleSettings.defaultMode()).displayRes)
        val adb = getString(if (ModuleSettings.defaultAdb()) R.string.settings_adb_on else R.string.settings_adb_off)
        return getString(R.string.settings_default_usb_summary, mode, adb)
    }

    private fun gameDndSummary(): String = if (ModuleSettings.gameDndEnabled()) {
        getString(R.string.game_dnd_summary, getString(R.string.game_dnd_enabled), ModuleSettings.gameDndPackages().size)
    } else {
        getString(R.string.game_dnd_disabled_summary)
    }

    private fun developerFooter(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(24), dp(12), dp(8))
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.developer_name)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(uiColor(R.color.text_tertiary))
        })
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.github_repository)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(uiColor(R.color.usb_accent))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedBackground(R.color.accent_soft, 14)
            clickFeedback(14)
            setOnClickListener { openGitHubRepository() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
    }

    private fun openGitHubRepository() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_repository_url)))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.github_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun valueRow(
        title: String,
        value: String,
        onValueBound: ((TextView) -> Unit)? = null,
        click: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        clickFeedback()
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { click() }
        addView(TextView(this@MainActivity).apply { text = title; textSize = 15f; setTextColor(uiColor(R.color.text_primary)) })
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = 12f
            setTextColor(uiColor(R.color.usb_text_secondary))
            setPadding(0, dp(3), 0, 0)
            onValueBound?.invoke(this)
        })
    }

    private fun toggleRow(title: String, checked: Boolean, changed: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(8), dp(10))
        addView(TextView(this@MainActivity).apply { text = title; textSize = 15f; setTextColor(uiColor(R.color.text_primary)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(SwitchMaterial(this@MainActivity).apply {
            useUsbManagerColors()
            isChecked = checked
            setOnCheckedChangeListener { _, value -> changed(value) }
        })
    }

    private fun primaryButton(label: String, click: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        cornerRadius = dp(16)
        useUsbManagerPrimaryColors()
        setOnClickListener { click() }
    }

    private fun outlinedButton(label: String, click: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        useUsbManagerOutlinedColors()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            marginStart = dp(8)
        }
        setOnClickListener { click() }
    }

    private fun showActivationGuide() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.activation_guide_title)
            .setMessage(R.string.activation_guide_message).setPositiveButton(R.string.dialog_got_it, null).showUsbDialog()
    }

    private fun showHowToGetLogs() {
        val tv = TextView(this).apply {
            text = getString(R.string.logs_how_to_message)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(uiColor(R.color.text_body))
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextIsSelectable(true)
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.logs_how_to_title)
            .setView(ScrollView(this).apply { addView(tv) }).setPositiveButton(R.string.dialog_got_it, null).showUsbDialog()
    }

    override fun onStart() {
        super.onStart()
        if (ModuleSettings.isFirstLaunchDone()) usbStatusPage.start()
        compatibilityCheckPending = true
    }

    override fun onResume() {
        super.onResume()
        mainResumed = true
        if (hasResumed) {
            defaultConfigSummaryView?.text = defaultConfigSummary()
            gameDndSummaryView?.text = gameDndSummary()
        }
        hasResumed = true
        checkCompatibilityOnEntry()
    }

    override fun onPause() {
        mainResumed = false
        super.onPause()
    }

    override fun onStop() {
        usbStatusPage.stop()
        cancelCompatibilityCheck()
        super.onStop()
    }

    override fun onDestroy() {
        usbStatusPage.destroy()
        cancelCompatibilityCheck()
        compatibilityWorker.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        savePageScroll()
        outState.putBoolean("appearance_changing", appearanceChanging)
        outState.putInt("page", selectedPage.ordinal)
        outState.putIntArray("page_scroll", pageScroll)
        super.onSaveInstanceState(outState)
    }
}
