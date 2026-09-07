package com.tiger.usbmanager

import android.app.Application
import android.content.Context

class UsbManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ModuleSettings.init(this)
        clearLegacyHostData()
    }

    /**
     * 迁移清理：本模块已移除「设备识别 + 记忆」功能，旧版本保存的电脑识别数据
     * （HostStore 的 JSON 数据库）不再使用，这里一次性清除，避免升级用户残留。
     */
    private fun clearLegacyHostData() {
        runCatching {
            getSharedPreferences(ModuleConstants.PREFS_HOSTS, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }
}
