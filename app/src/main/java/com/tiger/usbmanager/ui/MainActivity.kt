package com.tiger.usbmanager.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tiger.usbmanager.ModuleActivationCheck
import com.tiger.usbmanager.ModuleSettings
import com.tiger.usbmanager.R
import com.tiger.usbmanager.policy.HostInfo
import com.tiger.usbmanager.policy.HostStore
import com.tiger.usbmanager.policy.UsbMode

/**
 * Main entry point when launched from the desktop.
 *
 * - First launch: shows a full-screen feature / usage intro. Tapping "开始使用"
 *   marks the intro as seen and reloads the config-management view.
 * - Subsequent launches: shows all saved hosts with inline edit/delete, plus a
 *   module-settings section (default mode, default ADB, auto-off) and a button
 *   to re-open the intro.
 */
class MainActivity : Activity() {

    private lateinit var store: HostStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModuleSettings.init(this)
        store = HostStore.get(this)

        if (!ModuleSettings.isFirstLaunchDone()) {
            showIntro()
        } else {
            showConfigManager()
        }
    }

    // ------------------------------------------------------------------ Intro

    private fun showIntro() {
        val padding = dp(24)
        val topExtra = statusBarHeight()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_page))
            setPadding(padding, padding + topExtra, padding, padding)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 30f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 0, 0, dp(8))
        })

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("unknown")

        root.addView(TextView(this).apply {
            text = getString(R.string.intro_module_info, versionName)
            textSize = 14f
            setTextColor(getColor(R.color.text_tertiary))
            setPadding(0, 0, 0, dp(24))
        })

        root.addView(TextView(this).apply {
            text = "功能说明"
            textSize = 18f
            setTextColor(getColor(R.color.accent))
            setPadding(0, 0, 0, dp(12))
        })

        val features = listOf(
            "• USB 插入时自动检测连接事件（Hook UsbDeviceManager + 广播双路径）",
            "• 未识别电脑：弹出 USB 选择器对话框（后台启动受限则以通知全屏 Intent 弹出）",
            "• 可选模式：仅充电 / 文件传输 (MTP) / 图片传输 (PTP) / USB 网络共享 (RNDIS) / MIDI",
            "• 可一键开启 USB 调试（ADB）",
            "• 只使用本次连接中 adbd 确认的 RSA Host Key 识别电脑，不读取历史授权列表猜测",
            "• ADB 未运行或电脑尚未授权时无法取得 Host Key，将按未识别电脑处理",
            "• 已知可信电脑：自动应用保存的 USB 模式 + ADB 策略，无需弹窗",
            "• 未知电脑：弹窗询问，可选择「记住此电脑」保存策略",
            "• 拔出数据线后自动关闭 ADB（用户刚确定打开 ADB 时 30 秒内不关），确保 adbd 停止",
            "• 日志直接写入 LSPosed：打开 LSPosed Manager → 模块 → USBManager → ⋮ 菜单 → 查看日志",
        )
        features.forEach { line ->
            root.addView(TextView(this).apply {
                text = line
                textSize = 14f
                setTextColor(getColor(R.color.text_body))
                setPadding(dp(4), dp(6), dp(4), dp(6))
            })
        }

        root.addView(TextView(this).apply {
            text = "使用方法"
            textSize = 18f
            setTextColor(getColor(R.color.accent))
            setPadding(0, dp(20), 0, dp(12))
        })

        val usage = listOf(
            "1. 在 LSPosed 中启用本模块，作用域勾选「system」(API 102 的 system_server 专用 scope)",
            "2. 点击顶部「模块已激活」自检确认 Hook 生效，否则重启手机或执行：adb shell killall system_server",
            "3. 用数据线连接电脑，未知电脑会弹出 USB 选择器（如锁屏/后台则通过高优先级通知全屏弹出）",
            "4. 选择 USB 模式与 ADB 开关，勾选「记住此电脑」保存策略（可自定义命名）",
            "5. 下次连接同一电脑会自动应用，不再弹窗",
            "6. 顶部状态卡片点击「重新检测」可再次检查模块激活状态",
            "7. 点击「获取日志方法」查看如何从 LSPosed 中导出完整 Hook 日志",
        )
        usage.forEach { line ->
            root.addView(TextView(this).apply {
                text = line
                textSize = 14f
                setTextColor(getColor(R.color.text_body))
                setPadding(dp(4), dp(6), dp(4), dp(6))
            })
        }

        root.addView(Button(this).apply {
            text = getString(R.string.intro_start)
            setOnClickListener {
                ModuleSettings.markFirstLaunchDone()
                recreate()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(32) }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg_page))
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        })
    }

    // -------------------------------------------------------- Config manager

    private lateinit var hostListContainer: LinearLayout

    private lateinit var activationStatusContainer: LinearLayout

    private fun showConfigManager() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_page))
        }

        // Toolbar
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.bg_card))
            setPadding(dp(16), dp(14) + statusBarHeight(), dp(16), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 20f
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.settings_view_intro)
                textSize = 14f
                setTextColor(getColor(R.color.accent))
                setOnClickListener {
                    showIntro()
                }
                setPadding(dp(8), dp(4), dp(8), dp(4))
            })
        })

        // Module activation status banner (below toolbar, above scroll)
        activationStatusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        root.addView(activationStatusContainer)

        val scroll = ScrollView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Saved hosts section
        column.addView(sectionHeader(getString(R.string.settings_saved_hosts)))
        hostListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(hostListContainer)

        // Module settings section
        column.addView(sectionHeader(getString(R.string.settings_module_settings)))
        column.addView(settingsCard())

        scroll.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        setContentView(root)
        refreshActivationStatus()
    }

    /** Background-check module activation and render the banner on the UI thread. */
    private fun refreshActivationStatus() {
        activationStatusContainer.removeAllViews()
        val loadingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.banner_loading_bg))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = margin()
        }
        loadingRow.addView(ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            setPadding(0, 0, dp(10), 0)
        })
        loadingRow.addView(TextView(this).apply {
            text = "正在检测模块激活状态…"
            textSize = 14f
            setTextColor(getColor(R.color.banner_loading_text))
        })
        activationStatusContainer.addView(loadingRow)

        val handler = Handler(Looper.getMainLooper())
        Thread {
            val status = runCatching { ModuleActivationCheck.check(this) }
                .getOrElse { t -> ModuleActivationCheck.Status.Unknown("检测异常：${t.message}") }
            handler.post { renderActivationStatus(status) }
        }.apply { name = "usb-activation-check"; isDaemon = true }.start()
    }

    private fun renderActivationStatus(status: ModuleActivationCheck.Status) {
        activationStatusContainer.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = margin()
        }
        val title = TextView(this).apply { textSize = 15f }
        val body = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
            setTextColor(getColor(R.color.text_secondary))
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        when (status) {
            is ModuleActivationCheck.Status.Active -> {
                card.setBackgroundColor(getColor(R.color.banner_active_bg))
                title.text = "✅ 模块已激活 (Hook 正常)"
                title.setTextColor(getColor(R.color.banner_active_text))
                body.text = buildString {
                    append("USB Hook: ")
                    append(if (status.hasUsbDeviceManagerHook) "✔" else "✘")
                    append("   ADB Hook: ")
                    append(if (status.hasAdbHook) "✔" else "✘")
                    append("\n已记忆设备：${status.knownHostCount} 台\n模块包名：${status.packageName}")
                }
                btnRow.addView(recheckButton())
            }
            is ModuleActivationCheck.Status.Inactive -> {
                card.setBackgroundColor(getColor(R.color.banner_inactive_bg))
                title.text = "❌ 模块未生效（LSPosed 未注入 system_server）"
                title.setTextColor(getColor(R.color.banner_inactive_text))
                body.text = status.reason
                btnRow.addView(openLsposedGuideButton())
                btnRow.addView(recheckButton())
            }
            is ModuleActivationCheck.Status.Unknown -> {
                card.setBackgroundColor(getColor(R.color.banner_unknown_bg))
                title.text = "⚠️ 模块状态不明"
                title.setTextColor(getColor(R.color.banner_unknown_text))
                body.text = status.note
                btnRow.addView(openLsposedGuideButton())
                btnRow.addView(recheckButton())
            }
        }
        card.addView(title)
        card.addView(body)
        card.addView(btnRow)
        activationStatusContainer.addView(card)
    }

    private fun recheckButton(): Button = Button(this).apply {
        text = "重新检测"
        setOnClickListener { refreshActivationStatus() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        }
    }

    private fun openLsposedGuideButton(): Button = Button(this).apply {
        text = "激活教程"
        setOnClickListener {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("如何在 LSPosed 中激活本模块")
                .setMessage(
                    "1. 打开 LSPosed 管理器（LSPosed Manager / Magisk 中的 LSPosed 入口）\n\n" +
                        "2. 进入「模块」页，找到「USB 管理器」，点进详情\n\n" +
                        "3. 打开右上角的启用开关\n\n" +
                        "4. 在「作用域」(Scope) 中，勾选「system」（system_server 专用 scope）\n\n" +
                        "5. 返回模块列表，选择「重启」(Reboot) 或在终端执行：\n" +
                        "   adb shell killall system_server\n\n" +
                        "重启完成后回到本应用点击「重新检测」，显示「模块已激活」即成功。\n\n" +
                        "如果仍不生效，请在 LSPosed Manager 中查看模块日志。",
                )
                .setPositiveButton("知道了", null)
                .show()
        }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::hostListContainer.isInitialized) refreshHosts()
        if (::activationStatusContainer.isInitialized) refreshActivationStatus()
    }

    // ------------------------------------------------------- Host list UI

    private fun refreshHosts() {
        hostListContainer.removeAllViews()
        val hosts = store.list()
        if (hosts.isEmpty()) {
            hostListContainer.addView(TextView(this).apply {
                text = getString(R.string.settings_empty)
                setTextColor(getColor(R.color.text_tertiary))
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        hosts.forEach { host -> hostListContainer.addView(hostCard(host)) }
    }

    private fun hostCard(host: HostInfo): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.bg_card))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = margin()

        addView(TextView(this@MainActivity).apply {
            text = host.name
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
        })
        addView(TextView(this@MainActivity).apply {
            text = "Key: ${HostInfo.shortKey(host.hostKey)}  ·  ${host.mode.name}${if (host.adb) " + ADB" else ""}"
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(8))
        })
        addView(toggleRow(getString(R.string.settings_auto_label), host.auto) { checked ->
            store.upsert(host.copy(auto = checked))
        })

        val btnRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        btnRow.addView(Button(this@MainActivity).apply {
            text = getString(R.string.settings_edit)
            setOnClickListener { showEditDialog(host) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        btnRow.addView(Button(this@MainActivity).apply {
            text = getString(R.string.settings_delete)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.settings_delete)
                    .setMessage(host.name)
                    .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                        store.delete(host.hostKey)
                        refreshHosts()
                        Toast.makeText(this@MainActivity, R.string.settings_deleted, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(btnRow)
    }

    private fun showEditDialog(host: HostInfo) {
        val padding = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val nameInput = EditText(this).apply {
            hint = getString(R.string.chooser_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(host.name)
        }
        root.addView(nameInput)

        val radioGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val modeButtons = mutableMapOf<UsbMode, RadioButton>()
        UsbMode.entries.forEach { mode ->
            RadioButton(this).apply {
                text = getString(mode.displayRes)
                id = mode.ordinal
                isChecked = mode == host.mode
                modeButtons[mode] = this
                radioGroup.addView(this)
            }
        }
        root.addView(radioGroup)

        val adbCheck = CheckBox(this).apply {
            text = getString(R.string.chooser_adb)
            isChecked = host.adb
        }
        root.addView(adbCheck)

        val autoCheck = CheckBox(this).apply {
            text = getString(R.string.settings_auto_label)
            isChecked = host.auto
        }
        root.addView(autoCheck)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_edit)
            .setView(root)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val selectedMode = UsbMode.entries.firstOrNull {
                    radioGroup.checkedRadioButtonId == it.ordinal
                } ?: host.mode
                val updated = host.copy(
                    name = nameInput.text?.toString()?.ifBlank { host.name } ?: host.name,
                    usbMode = selectedMode.wireValue,
                    adb = adbCheck.isChecked,
                    auto = autoCheck.isChecked,
                )
                store.upsert(updated)
                refreshHosts()
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ------------------------------------------------------- Settings card

    private fun settingsCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.bg_card))
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = margin()

        addView(row(getString(R.string.settings_default_mode), ModuleSettings.defaultMode().uppercase()) {
            showModePicker()
        })
        addView(toggleRow(getString(R.string.settings_default_adb), ModuleSettings.defaultAdb()) { checked ->
            ModuleSettings.prefs().edit().putBoolean(ModuleSettings.KEY_DEFAULT_ADB, checked).apply()
        })
        addView(toggleRow(getString(R.string.settings_disconnect_auto_off), ModuleSettings.disconnectAutoOffAdb()) { checked ->
            ModuleSettings.prefs().edit().putBoolean(ModuleSettings.KEY_DISCONNECT_AUTO_OFF_ADB, checked).apply()
        })
        addView(Button(this@MainActivity).apply {
            text = getString(R.string.settings_clear_all)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.settings_clear_all)
                    .setMessage(R.string.settings_clear_confirm)
                    .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                        store.clear()
                        refreshHosts()
                        Toast.makeText(this@MainActivity, R.string.settings_cleared, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()
            }
        })
        addView(Button(this@MainActivity).apply {
            text = "获取日志方法"
            setOnClickListener { showHowToGetLogs() }
        })
    }

    // ------------------------------------------------------- Log viewer (replaced by how-to)

    private fun showHowToGetLogs() {
        val msg = """1. 打开 LSPosed Manager（或 LSPosed 模块管理器）
2. 进入「模块」标签页
3. 找到 USBManager，点击进入详情页
4. 点右上角 ⋮（更多）菜单
5. 点「查看日志」→ 就可以看到完整的 Hook 日志，包括：
     • 模块是否被注入 system_server
     • 每次插入/拔出 USB 的事件
     • 用户选择后 APPLY_USB_CONFIG 是否被收到
     • 模式切换、ADB 开关是否成功执行

【备选：命令行】
  adb logcat -c && adb logcat -s USBManager:V LSPosedFramework:V

（写入 Android/data/.../logs/ 的方案在 NothingOS/Android 16
下会因为 system_server 无其它 app 存储目录写权限而失败，
所以统一用 LSPosed 内置日志查看器。）"""
        val padding = dp(16)
        val tv = TextView(this).apply {
            text = msg.trimIndent()
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.text_body))
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("获取 USBManager Hook 日志")
            .setView(scroll)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showModePicker() {
        val labels = UsbMode.entries.map { getString(it.displayRes) }.toTypedArray()
        val current = UsbMode.fromWire(ModuleSettings.defaultMode())
        val checked = UsbMode.entries.indexOf(current)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_default_mode)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val mode = UsbMode.entries[which]
                ModuleSettings.prefs().edit().putString(ModuleSettings.KEY_DEFAULT_MODE, mode.wireValue).apply()
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ------------------------------------------------------- Helpers

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 13f
        setTextColor(getColor(R.color.text_tertiary))
        setPadding(0, dp(12), 0, dp(8))
    }

    private fun row(title: String, value: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTextColor(getColor(R.color.text_subtitle))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 14f
                setTextColor(getColor(R.color.accent))
            })
        }

    private fun toggleRow(title: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTextColor(getColor(R.color.text_subtitle))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(CheckBox(this@MainActivity).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
            })
        }

    private fun margin(): ViewGroup.MarginLayoutParams =
        ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(24)
    }
}
