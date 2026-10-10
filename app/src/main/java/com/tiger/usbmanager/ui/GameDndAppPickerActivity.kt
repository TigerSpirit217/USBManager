package com.tiger.usbmanager.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.LruCache
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.FrameLayout
import androidx.appcompat.widget.PopupMenu
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.tiger.usbmanager.ModuleSettings
import com.tiger.usbmanager.R
import com.tiger.usbmanager.bridge.InstalledPackageCatalog
import com.tiger.usbmanager.bridge.CatalogApp
import java.text.Collator
import java.util.concurrent.Executors

class GameDndAppPickerActivity : LocalizedActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val iconWorker = Executors.newSingleThreadExecutor()
    private val iconCache = LruCache<String, Bitmap>(48)
    private val iconsInFlight = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val selected = mutableSetOf<String>()
    private var initiallySelected = emptySet<String>()
    private var packages = emptyList<CatalogApp>()
    private var visiblePackages = emptyList<CatalogApp>()
    private var showSystemApps = false
    private var catalogLoaded = false
    private val appAdapter = AppAdapter()
    private val labelCollator by lazy { Collator.getInstance(resources.configuration.locales[0]) }
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var save: MaterialButton
    private lateinit var searchPanel: MaterialCardView
    private lateinit var searchAction: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModuleSettings.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        selected.addAll(savedInstanceState?.getStringArrayList("selection") ?: ModuleSettings.gameDndPackages())
        // Keep the initial grouping for the entire editing session, including rotation.
        initiallySelected = savedInstanceState?.getStringArrayList("initial_selection")?.toSet() ?: selected.toSet()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(uiColor(R.color.bg_page))
            applySystemBarPadding(includeHorizontal = true)
        }
        val heading = toolbar(getString(R.string.game_dnd_picker_title), back = { finish() }).apply {
            applySystemBarPadding(includeTop = true)
        }
        searchAction = toolbarAction(R.drawable.ic_search, R.string.game_dnd_search_action) { toggleSearch() }
        heading.addView(searchAction, actionParams())
        heading.addView(toolbarAction(R.drawable.ic_more, R.string.game_dnd_more) { showOptions(it) }, actionParams())
        root.addView(heading)
        search = EditText(this).apply {
            setHint(R.string.game_dnd_search)
            setTextColor(uiColor(R.color.text_primary))
            setHintTextColor(uiColor(R.color.text_tertiary))
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_FILTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        searchPanel = surfaceCard(16).apply {
            visibility = View.GONE
            addView(search)
        }
        root.addView(searchPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(18); marginEnd = dp(18); topMargin = dp(4); bottomMargin = dp(4)
        })
        showSystemApps = ModuleSettings.prefs().getBoolean(ModuleSettings.KEY_GAME_DND_SHOW_SYSTEM_APPS, false)
        status = TextView(this).apply {
            setText(R.string.game_dnd_loading)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(uiColor(R.color.on_accent_soft))
            background = roundedBackground(R.color.accent_soft, 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        list = ListView(this).apply {
            adapter = appAdapter
            divider = ColorDrawable(uiColor(R.color.outline))
            dividerHeight = dp(1)
            setOnItemClickListener { _, _, position, _ ->
                val name = visiblePackages[position].packageName
                if (!selected.add(name)) selected.remove(name)
                appAdapter.notifyDataSetChanged()
                updateSave()
            }
        }
        val listFrame = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                marginStart = dp(18); marginEnd = dp(18)
            })
        }
        list.emptyView = status
        root.addView(surfaceCard().apply {
            clipToOutline = true
            addView(listFrame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            marginStart = dp(18); marginEnd = dp(18); topMargin = dp(8); bottomMargin = dp(8)
        })
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(dp(18), dp(8), dp(18), dp(12))
            applySystemBarPadding(includeBottom = true, includeIme = true)
        }
        save = MaterialButton(this).apply {
            isAllCaps = false
            cornerRadius = dp(16)
            useUsbManagerPrimaryColors()
            setOnClickListener {
                ModuleSettings.prefs().edit { putStringSet(ModuleSettings.KEY_GAME_DND_PACKAGES, selected.toSet()) }
                finish()
            }
        }
        footer.addView(save)
        root.addView(footer)
        setContentView(root)
        search.doAfterTextChanged { filter() }
        search.setText(savedInstanceState?.getString("search").orEmpty())
        if (savedInstanceState?.getBoolean("search_expanded") == true || search.text.isNotEmpty()) {
            toggleSearch(show = true, requestKeyboard = false)
        }
        updateSave()
        filter()
        worker.execute {
            val result = runCatching { InstalledPackageCatalog.load(applicationContext) }
            if (!Thread.currentThread().isInterrupted) handler.post {
                if (isDestroyed || isFinishing) return@post
                result.onSuccess { names ->
                    packages = names
                    catalogLoaded = true
                    filter()
                }.onFailure {
                    status.setText(R.string.game_dnd_load_failed)
                    status.setTextColor(uiColor(R.color.game_dnd_warning))
                    status.background = roundedBackground(R.color.banner_inactive_bg, 16)
                }
            }
        }
    }

    private fun filter() {
        val query = search.text.toString().trim()
        val knownPackages = packages.mapTo(mutableSetOf()) { it.packageName }
        // Hidden system apps retain their selection. Missing apps stay removable
        // after the catalog finishes, even when unchecked during this session.
        val missing = if (catalogLoaded) initiallySelected.filter { it !in knownPackages }
            .map { CatalogApp(it, it) } else emptyList()
        visiblePackages = (packages + missing)
            .filter { (showSystemApps || !it.isSystem) &&
                (it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)) }
            .sortedWith { a, b ->
                val selectionOrder = (if (a.packageName in initiallySelected) 0 else 1) - (if (b.packageName in initiallySelected) 0 else 1)
                if (selectionOrder != 0) return@sortedWith selectionOrder
                val labelOrder = labelCollator.compare(a.label, b.label)
                if (labelOrder != 0) labelOrder else a.packageName.compareTo(b.packageName)
            }
        appAdapter.notifyDataSetChanged()
        if (catalogLoaded) status.setText(R.string.game_dnd_no_apps)
    }

    private fun actionParams() = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) }

    private fun toolbarAction(icon: Int, description: Int, click: (View) -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(uiColor(R.color.text_primary))
        contentDescription = getString(description)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = roundedBackground(R.color.surface_variant, 16)
        isFocusable = true
        clickFeedback()
        setOnClickListener { click(it) }
    }

    private fun toggleSearch(show: Boolean = searchPanel.visibility != View.VISIBLE, requestKeyboard: Boolean = true) {
        if (UiMotion.enabled()) android.transition.TransitionManager.beginDelayedTransition(
            searchPanel.parent as ViewGroup, android.transition.AutoTransition().setDuration(180))
        searchPanel.visibility = if (show) View.VISIBLE else View.GONE
        searchAction.setImageResource(if (show) R.drawable.ic_close else R.drawable.ic_search)
        searchAction.contentDescription = getString(if (show) R.string.game_dnd_close_search else R.string.game_dnd_search_action)
        val input = WindowCompat.getInsetsController(window, search)
        if (show && requestKeyboard) {
            search.requestFocus()
            search.post { if (!isFinishing && !isDestroyed) input.show(WindowInsetsCompat.Type.ime()) }
        } else if (!show) {
            search.text.clear()
            search.clearFocus()
            input.hide(WindowInsetsCompat.Type.ime())
        }
    }

    private fun showOptions(anchor: View) {
        PopupMenu(this, anchor, Gravity.END).apply {
            menu.add(R.string.game_dnd_show_system).apply { isCheckable = true; isChecked = showSystemApps }
            setOnMenuItemClickListener { item ->
                showSystemApps = !showSystemApps
                item.isChecked = showSystemApps
                ModuleSettings.prefs().edit { putBoolean(ModuleSettings.KEY_GAME_DND_SHOW_SYSTEM_APPS, showSystemApps) }
                filter()
                true
            }
            show()
        }
    }

    private data class AppRow(val icon: ImageView, val label: TextView, val packageName: TextView, val check: CheckBox)

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = visiblePackages.size
        override fun getItem(position: Int) = visiblePackages[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LinearLayout(this@GameDndAppPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(72)
                setPadding(dp(16), dp(12), dp(10), dp(12))
                val icon = ImageView(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
                val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val label = TextView(context).apply {
                    textSize = 16f
                    setTextColor(uiColor(R.color.text_primary))
                }
                val packageName = TextView(context).apply {
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(uiColor(R.color.text_secondary))
                    setPadding(0, dp(3), 0, 0)
                }
                texts.addView(label)
                texts.addView(packageName)
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val check = CheckBox(context).apply {
                    useUsbManagerColors()
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                addView(check)
                tag = AppRow(icon, label, packageName, check)
            }
            val views = row.tag as AppRow
            val app = getItem(position)
            views.label.text = app.label
            views.packageName.text = app.packageName
            views.check.isChecked = app.packageName in selected
            row.setBackgroundColor(uiColor(if (views.check.isChecked) R.color.usb_accent_soft else R.color.bg_card))
            bindIcon(app, views.icon)
            return row
        }
    }

    private fun bindIcon(app: CatalogApp, view: ImageView) {
        view.tag = app.packageName
        val cached = iconCache.get(app.packageName)
        if (cached != null) { view.setImageBitmap(cached); return }
        view.setImageDrawable(packageManager.defaultActivityIcon)
        if (isDestroyed || !iconsInFlight.add(app.packageName)) return
        iconWorker.execute {
            val bitmap = runCatching {
                val drawable = app.applicationInfo?.loadIcon(packageManager) ?: packageManager.defaultActivityIcon
                val size = dp(40).coerceIn(48, 144)
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(Canvas(it))
                }
            }.getOrNull()
            if (!Thread.currentThread().isInterrupted) handler.post {
                if (isDestroyed || isFinishing) return@post
                iconsInFlight.remove(app.packageName)
                if (bitmap != null) {
                    iconCache.put(app.packageName, bitmap)
                    // A recycled row must never receive another app's icon.
                    appAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun updateSave() { save.text = getString(R.string.game_dnd_save_count, selected.size) }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selection", ArrayList(selected))
        outState.putStringArrayList("initial_selection", ArrayList(initiallySelected))
        outState.putString("search", search.text.toString())
        outState.putBoolean("search_expanded", searchPanel.visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        iconWorker.shutdownNow()
        iconCache.evictAll()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
