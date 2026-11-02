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
import androidx.core.view.doOnLayout
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
    private var contentHost: PageSwipeHost? = null
    private val pageViews = linkedMapOf<Page, ScrollView>()
    private val developerFooters = linkedMapOf<Page, LinearLayout>()
    private var navigation: AppNavigationBar? = null
    private var navigationHost: View? = null
    private var mainRoot: LinearLayout? = null
    private var mainBody: FrameLayout? = null
    private var navigationFloating = false
    private var navigationSide = 0
    private var navigationGravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    private var navigationGeneration = 0
    private var appearanceChanging = false
    private var themeColorDialog: ThemeColorDialog? = null
    private val usbStatusPage by lazy { UsbStatusPage(this) }
    private lateinit var activationStatusContainer: LinearLayout
    private var lastActivationStatus: ModuleActivationCheck.Status? = null
    private val activationHandler = Handler(Looper.getMainLooper())
    private var activationGeneration = 0
    private var activationChecking = false
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
        savedInstanceState?.getString("activation_package")?.let {
            lastActivationStatus = ModuleActivationCheck.Status.Active(it,
                savedInstanceState.getBoolean("activation_usb_hook"), savedInstanceState.getBoolean("activation_adb_hook"),
                savedInstanceState.getString("activation_details"))
        }
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
        savedInstanceState?.getBundle("theme_color_editor")?.let(::showThemeColorDialog)
    }

    private fun showIntro() {
        savePageScroll()
        mainScreenVisible = false
        cancelActivationCheck()
        usbStatusPage.setVisible(false)
        cancelNavigationTransition()
        contentHost?.cancelScroll()
        cancelCompatibilityCheck()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
            applySystemBarPadding(includeTop = true, includeBottom = true, includeHorizontal = true)
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
            val features = infoCard(
                getString(R.string.intro_section_features),
                listOf(R.string.intro_feature_1, R.string.intro_feature_2, R.string.intro_feature_3, R.string.intro_feature_4,
                    R.string.intro_feature_5, R.string.intro_feature_6, R.string.intro_feature_7),
            )
            val usage = infoCard(
                getString(R.string.intro_section_usage),
                listOf(R.string.intro_usage_1, R.string.intro_usage_2, R.string.intro_usage_3,
                    R.string.intro_usage_4, R.string.intro_usage_5, R.string.intro_usage_6),
            )
            addView(adaptiveColumns(features, usage), verticalMargins(top = dp(8), bottom = 0))
            addView(primaryButton(getString(R.string.intro_start)) {
                ModuleSettings.markFirstLaunchDone()
                recreate()
            }, verticalMargins(top = dp(12), bottom = 0))
        }
        setContentView(responsiveScreen(ScrollView(this).apply {
            setBackgroundColor(uiColor(R.color.bg_page))
            isFillViewport = true
            addView(column)
        }))
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
        cancelNavigationTransition()
        cancelActivationCheck()
        mainScreenVisible = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(uiColor(R.color.bg_page))
            applySystemBarPadding(includeHorizontal = true)
        }
        mainRoot = root
        contentHost?.cancelScroll()
        pageViews.clear()
        developerFooters.clear()
        defaultConfigSummaryView = null
        gameDndSummaryView = null
        contentHost = PageSwipeHost(this, selectedPage.ordinal,
            progress = { navigation?.preview(it) }, selected = { onPageSettled(Page.entries[it]) },
            destinationChanged = { navigation?.selectDestination(it) },
            swipeStarted = { navigation?.followPage() })
        mainBody = FrameLayout(this).apply {
            clipChildren = false
            addView(contentHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(mainBody, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        Page.entries.forEach { page ->
            contentHost?.addView(createPage(page), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        installNavigationBar()
        usbStatusPage.setVisible(selectedPage == Page.STATUS)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) usbStatusPage.start()
        setContentView(responsiveScreen(root))
        refreshActivationStatus()
        checkCompatibilityOnEntry()
        if (appearanceChanging) { UiMotion.enter(root, 0); appearanceChanging = false }
    }

    @android.annotation.SuppressLint("RtlHardcoded") // User-selected left/right positions refer to physical screen edges.
    private fun barGravity(): Int {
        val side = DisplaySettings.navigationSide(this)
        return when {
            side < 0 -> Gravity.LEFT or Gravity.CENTER_VERTICAL
            side > 0 -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
            landscape() && DisplaySettings.floating() && DisplaySettings.floatingPosition() == DisplaySettings.FloatingPosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.RIGHT
            else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
    }

    @android.annotation.SuppressLint("RtlHardcoded")
    private fun installNavigationBar(animated: Boolean = false) {
        val root = mainRoot ?: return
        val body = mainBody ?: return
        val floating = DisplaySettings.floating()
        val side = DisplaySettings.navigationSide(this)
        val vertical = side != 0
        val gravity = barGravity()
        navigationFloating = floating
        navigationSide = side
        navigationGravity = gravity
        root.orientation = if (!floating && vertical) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        body.layoutParams = if (!floating && vertical) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        navigation = AppNavigationBar(this, floating, Page.entries.map { it.title to it.icon }, selectedPage.ordinal, vertical) {
            selectPage(Page.entries[it])
        }
        val host: View = if (floating) object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                if (vertical) navigation?.layoutParams?.height = minOf(dp(320),
                    (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0))
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            clipChildren = false
            setPadding(dp(18), if (vertical) dp(12) else 0, dp(18), dp(if (landscape()) 8 else 12))
            applySystemBarPadding(includeTop = vertical, includeBottom = true)
            val barWidth = minOf(dp(320), dp(resources.configuration.screenWidthDp - 36))
            addView(navigation, FrameLayout.LayoutParams(if (vertical) dp(64) else barWidth,
                if (vertical) dp(320) else dp(64), gravity))
        } else LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(uiColor(R.color.bg_card))
            applySystemBarPadding(includeTop = vertical, includeBottom = true)
            val line = View(this@MainActivity).apply { setBackgroundColor(uiColor(R.color.outline)) }
            val lineParams = if (vertical) LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            val lineFirst = !vertical || (side > 0) != (layoutDirection == View.LAYOUT_DIRECTION_RTL)
            if (lineFirst) addView(line, lineParams)
            addView(navigation, if (vertical) LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.MATCH_PARENT)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (landscape()) 64 else 80)))
            if (!lineFirst) addView(line, lineParams)
        }
        navigationHost = host
        if (floating) body.addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            if (vertical) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        else if (vertical) root.addView(host, if ((side < 0) != (root.layoutDirection == View.LAYOUT_DIRECTION_RTL)) 0 else root.childCount,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        else root.addView(host)
        contentHost?.apply {
            layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = if (floating && side < 0) dp(88) else 0
                rightMargin = if (floating && side > 0) dp(88) else 0
            }
            setVerticalPaging(vertical)
        }
        pageViews.values.forEach { scroll ->
            scroll.setPadding(0, 0, 0, if (floating && !vertical) dp(if (landscape()) 80 else 88) else 0)
            scroll.applySystemBarPadding(includeBottom = floating || vertical)
        }
        alignDeveloperFooters(animated)
        if (animated && UiMotion.enabled()) {
            val generation = navigationGeneration
            host.doOnLayout {
                if (generation == navigationGeneration && mainScreenVisible && !isDestroyed) {
                    if (vertical) host.translationX = (side * ((navigation?.width ?: dp(80)) + dp(36))).toFloat()
                    else host.translationY = (host.height + dp(24)).toFloat()
                    host.animate().translationX(0f).translationY(0f).setDuration(240)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
            }
        }
    }

    private fun createPage(page: Page): LinearLayout {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(24))
            when (page) {
                Page.HOME -> {
                    activationStatusContainer = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                    lastActivationStatus?.let(::renderActivationStatus)
                    val overview = verticalContent(activationStatusContainer,
                        sectionLabel(getString(R.string.auth_section_title)), authenticationCard())
                    val settings = verticalContent(settingsCard())
                    if (twoColumns()) addView(adaptiveColumns(overview, settings)) else {
                        // Preserve the established portrait order.
                        overview.removeView(activationStatusContainer); addView(activationStatusContainer)
                        addView(settings, verticalMargins(top = dp(12), bottom = 0))
                        addView(overview)
                    }
                }
                Page.STATUS -> addView(usbStatusPage.content())
                Page.SETTINGS -> {
                    addView(AppearancePage(this@MainActivity,
                        themeChanged = { changeAppearance() }, barChanged = { changeNavigationBar() },
                        layoutChanged = { savePageScroll(); DisplaySettings.applyOrientation(this@MainActivity) },
                        editThemeColor = { showThemeColorDialog() }).content())
                    addView(sectionLabel(getString(R.string.settings_advanced_section)))
                    addView(surfaceCard().apply {
                        addView(valueRow(getString(R.string.action_get_logs), getString(R.string.settings_logs_description)) { showHowToGetLogs() })
                    })
                }
            }
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(developerFooter(page), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            if (DisplaySettings.floating()) {
                setPadding(0, 0, 0, dp(if (landscape()) 80 else 88))
                applySystemBarPadding(includeBottom = true)
            }
            addView(column)
        }
        pageViews[page] = scroll
        scroll.post { scroll.scrollTo(0, pageScroll[page.ordinal]) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                applySystemBarPadding(includeTop = true)
                addView(toolbar(getString(if (page == Page.HOME) R.string.app_name else page.title),
                    action = if (page == Page.HOME) getString(R.string.settings_view_intro) to { showIntro() } else null).apply {
                    setPadding(dp(18), paddingTop, dp(18), paddingBottom)
                    minimumHeight = dp(if (landscape()) 52 else 64)
                })
            })
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun savePageScroll() {
        pageViews.forEach { (page, scroll) -> pageScroll[page.ordinal] = scroll.scrollY }
    }

    private fun selectPage(page: Page) {
        contentHost?.setPage(page.ordinal)
    }

    private fun onPageSettled(page: Page) {
        val changed = selectedPage != page
        selectedPage = page
        usbStatusPage.setVisible(page == Page.STATUS)
        navigation?.select(page.ordinal, false)
        if (changed && page == Page.HOME) refreshActivationStatus()
        checkCompatibilityOnEntry()
    }

    private fun showThemeColorDialog(savedState: Bundle? = null) {
        if (themeColorDialog != null) return
        themeColorDialog = ThemeColorDialog(this,
            save = { DisplaySettings.setSeed(it); changeAppearance() },
            dismissed = { themeColorDialog = null }).also { it.show(savedState) }
    }

    private fun changeAppearance() {
        savePageScroll()
        cancelNavigationTransition()
        appearanceChanging = true
        recreate()
    }

    private fun cancelNavigationTransition() {
        navigationGeneration++
        navigationHost?.animate()?.cancel()
        navigation?.animate()?.cancel()
        developerFooters.values.forEach { it.animate().cancel() }
    }

    private fun changeNavigationBar() {
        savePageScroll()
        val host = navigationHost
        if (!UiMotion.enabled() || host == null || !host.isAttachedToWindow) {
            (host?.parent as? ViewGroup)?.removeView(host)
            installNavigationBar(); return
        }
        cancelNavigationTransition()
        val generation = navigationGeneration
        val nextGravity = barGravity()
        if (landscape() && navigationFloating && DisplaySettings.floating() && navigationSide == 0 &&
            DisplaySettings.navigationSide(this) == 0 && nextGravity != navigationGravity) {
            // Reuse the capsule so its selection and drag state survive a horizontal relocation.
            navigationGravity = nextGravity
            host.animate().translationX(0f).translationY(0f).setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            navigation?.let { alignComponent(it, nextGravity, animated = true) }
            alignDeveloperFooters(animated = true)
            return
        }
        if (DisplaySettings.floating() == navigationFloating && DisplaySettings.navigationSide(this) == navigationSide && barGravity() == navigationGravity) {
            host.animate().translationX(0f).translationY(0f).setDuration(180).start()
            return
        }
        val motion = host.animate()
        if (navigationSide != 0) motion.translationX((navigationSide * ((navigation?.width ?: dp(80)) + dp(36))).toFloat())
        else motion.translationY((host.height + dp(24)).toFloat())
        motion.setDuration(180)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                if (generation == navigationGeneration && mainScreenVisible && !isFinishing && !isDestroyed) {
                    (host.parent as? ViewGroup)?.removeView(host)
                    installNavigationBar(animated = true)
                }
            }.start()
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
        if (activationChecking || !mainScreenVisible || isDestroyed || isFinishing) return
        activationChecking = true
        val request = ++activationGeneration
        val target = activationStatusContainer
        var loadingShown = false
        val showLoading = Runnable {
            if (request == activationGeneration && mainScreenVisible && !isDestroyed && activationStatusContainer === target) {
                loadingShown = true
                showActivationLoading()
            }
        }
        // Keep the last confirmed active card visible during quick re-checks.
        if (lastActivationStatus is ModuleActivationCheck.Status.Active) activationHandler.postDelayed(showLoading, 1_000L)
        else showLoading.run()
        Thread {
            val status = runCatching { ModuleActivationCheck.check(this) }.getOrElse {
                android.util.Log.w("USBManager", "Activation check failed", it)
                ModuleActivationCheck.Status.Unknown(getString(R.string.activate_check_error))
            }
            activationHandler.post {
                if (request != activationGeneration || isDestroyed || activationStatusContainer !== target) return@post
                activationChecking = false
                activationHandler.removeCallbacks(showLoading)
                if (loadingShown || lastActivationStatus != status) renderActivationStatus(status)
            }
        }.apply { name = "usb-activation-check"; isDaemon = true }.start()
    }

    private fun cancelActivationCheck() {
        activationGeneration++
        activationChecking = false
        activationHandler.removeCallbacksAndMessages(null)
    }

    private fun showActivationLoading() {
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
    }

    private fun renderActivationStatus(status: ModuleActivationCheck.Status) {
        if (!::activationStatusContainer.isInitialized || !mainScreenVisible || isDestroyed) return
        lastActivationStatus = status
        activationStatusContainer.removeAllViews()
        val view = when (status) {
            is ModuleActivationCheck.Status.Active -> statusCard(
                R.color.banner_active_bg, R.color.banner_active_text,
                getString(R.string.activate_state_active),
                null,
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

    @android.annotation.SuppressLint("RtlHardcoded")
    private fun footerGravity(): Int = Gravity.TOP or if (landscape() && DisplaySettings.floating() &&
        DisplaySettings.floatingPosition() == DisplaySettings.FloatingPosition.BOTTOM_RIGHT) Gravity.RIGHT else Gravity.CENTER_HORIZONTAL

    private fun alignDeveloperFooters(animated: Boolean) {
        developerFooters.values.forEach { alignComponent(it, footerGravity(), animated) }
    }

    private fun alignComponent(view: View, gravity: Int, animated: Boolean) {
        view.animate().cancel()
        val previousX = view.x
        view.layoutParams = (view.layoutParams as FrameLayout.LayoutParams).apply { this.gravity = gravity }
        if (!animated || !view.isAttachedToWindow || !UiMotion.enabled()) {
            view.translationX = 0f
            return
        }
        val generation = navigationGeneration
        view.doOnLayout {
            if (generation == navigationGeneration && mainScreenVisible && !isDestroyed) {
                view.translationX = previousX - view.left
                view.animate().translationX(0f).setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }
    }

    private fun developerFooter(page: Page): FrameLayout {
        val contents = developerFooterContents()
        developerFooters[page] = contents
        return object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Match the capsule's width and horizontal travel above the bottom navigation.
                contents.layoutParams.width = minOf(dp(320), MeasureSpec.getSize(widthMeasureSpec))
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            addView(contents, FrameLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT, footerGravity()))
        }
    }

    private fun developerFooterContents(): LinearLayout = LinearLayout(this).apply {
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
            setTextColor(uiColor(R.color.on_accent_soft))
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
        DisplaySettings.applyOrientation(this)
        usbStatusPage.setVisible(mainScreenVisible && selectedPage == Page.STATUS)
        if (hasResumed) {
            defaultConfigSummaryView?.text = defaultConfigSummary()
            gameDndSummaryView?.text = gameDndSummary()
            if (mainScreenVisible) refreshActivationStatus()
        }
        hasResumed = true
        checkCompatibilityOnEntry()
    }

    override fun onPause() {
        mainResumed = false
        super.onPause()
    }

    override fun onStop() {
        usbStatusPage.setVisible(false)
        usbStatusPage.stop()
        cancelCompatibilityCheck()
        super.onStop()
    }

    override fun onDestroy() {
        themeColorDialog?.dismiss()
        cancelActivationCheck()
        cancelNavigationTransition()
        usbStatusPage.destroy()
        cancelCompatibilityCheck()
        compatibilityWorker.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        themeColorDialog?.saveState()?.let { outState.putBundle("theme_color_editor", it) }
        savePageScroll()
        (lastActivationStatus as? ModuleActivationCheck.Status.Active)?.let {
            outState.putString("activation_package", it.packageName)
            outState.putBoolean("activation_usb_hook", it.hasUsbDeviceManagerHook)
            outState.putBoolean("activation_adb_hook", it.hasAdbHook)
            outState.putString("activation_details", it.details)
        }
        outState.putBoolean("appearance_changing", appearanceChanging)
        outState.putInt("page", selectedPage.ordinal)
        outState.putIntArray("page_scroll", pageScroll)
        super.onSaveInstanceState(outState)
    }
}
