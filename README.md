# USBManager — Android USB 管理模块

[English](README_ENG.md)

一个基于 **LSPosed** 框架的 Android 系统模块，每次用数据线连接电脑时弹出 USB 选择器，让你即时决定本次的 USB 模式与是否开启 ADB。

> 现已移除「设备识别 + 记忆」功能。ADB 设备端无法可靠读取主控端（电脑）的 PID/VID，导致不同电脑之间可能被错误识别为同一台、从而套用别的电脑配置。因此本模块不再记忆任何电脑，改为**每次连接都弹窗**。

## 功能

* **自动检测连接**：Hook `UsbDeviceManager`（辅以系统 `USB_STATE` 广播双路径），检测手机以设备模式连接电脑的事件

* **每次连接弹窗**：每次连接电脑都弹出 USB 选择器，选择本次的 USB 模式与 ADB 开关，不再记忆/自动套用

* **多种模式**：仅充电 / 文件传输 (MTP) / 图片传输 (PTP) / USB 网络共享 (RNDIS) / MIDI

* **ADB 一键开关**：在弹窗中决定本次是否开启 USB 调试

* **OTG 不弹窗**：手机作为 USB 主机（OTG 接入 U 盘、键鼠等）时不弹窗，交由系统原生识别，也无需开启 ADB

* **拔线自动关 ADB**：拔出数据线后自动关闭 ADB（可关闭该行为），确保 adbd 停止

* **Root 回退**：当框架 API 不可用时自动尝试 `su` 命令写入系统属性（要求能使用 root 权限）

## 安装

### 前置条件

* 已解锁 Bootloader 并 Root 的 Android 设备

* 已安装 **LSPosed**

* Android 12+（推荐）或 11

### 步骤

1. 从 [Releases](../../releases) 下载最新 APK
2. 安装 APK 到设备
3. 打开 **LSPosed Manager** → 模块列表 → 勾选 **USBManager**
4. **作用域**：勾选 `system`（系统框架）
5. 重启设备
6. 从桌面启动 USBManager 应用，查看使用说明与模块设置

## 使用

1. 插入 USB 数据线连接到电脑
2. 每次都会弹出 USB 选择器（如锁屏/后台受限，则以高优先级通知全屏弹出）
3. 选择 USB 模式（仅充电 / 文件传输 / 图片传输 / 网络共享 / MIDI）和 ADB 开关
4. 点击「确定」生效

## 默认设置

* 默认 USB 模式：**仅充电**

* 默认开启 USB 调试：**关闭**

* 拔线自动关闭 ADB：**开启**

* 锁屏时是否弹出选择窗口：**关闭**（默认解锁后再弹）

可通过 USBManager 应用修改默认值。

> **升级说明**：从旧版本升级后，首次启动会清除旧版本保存的电脑识别数据（`usbmanager_hosts` 及 system_server 侧的 fallback 副本），避免残留数据影响新行为。

## 构建

```bash
# 克隆仓库
git clone https://github.com/your-username/USBManager.git
cd USBManager

# 使用 Gradle 构建
./gradlew :app:assembleRelease
```

## 调试

### 查看日志

模块日志输出到 `USBManager` tag。可通过 LSPosed Manager 查看：

1. 打开 **LSPosed Manager**
2. 点击 **日志** → 搜索 **USBManager**
3. 查看相关日志

或者使用 ADB Logcat：

```bash
adb logcat -s USBManager
```

关键日志标签：

* `[WATCHER]` — USB 事件处理流程

* `[RX]` — 广播接收器

* `[HOOK]` — Hook 初始化

* `[CLIENT]` — ContentProvider 通信

* `[CONTROLLER]` — USB 模式/ADB 配置应用

## 许可证

本项目使用木兰公共许可证，第 2 版（Mulan PubL v2）。
完整授权见 [LICENSE](https://license.coscl.org.cn/MulanPubL-2.0)。

## 源码与发布

* 源码仓库：<https://github.com/TigerSpirit217/USBManager>

* 发布页面：<https://github.com/TigerSpirit217/USBManager/releases>

* 反馈Issues：<https://github.com/TigerSpirit217/USBManager/issues>