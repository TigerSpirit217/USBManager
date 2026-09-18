# USBManager — Android USB 管理模块

[English](README_ENG.md)

一个基于 **LSPosed** 框架的 Android 系统模块。手机通过数据线连接电脑时，它会显示 USB 选择窗口，让用户决定本次连接的 USB 模式和 ADB 状态。

-----

<details>
<summary><h2>实验性功能（点击展开）</h2></summary>

该功能用于让手机识别并记住可信电脑。功能默认关闭，关闭时仍会在每次连接电脑时显示 USB 选择窗口。

* 首次使用时，请先进行本地检测：
>1. 在 root 管理器中授予本软件 root 权限。
>2. 重新打开应用，进入“电脑识别与记忆”页面，点击检测。（检测完全在手机本地完成，不要求先连接数据线或电脑。）
>3. 若显示“此设备支持电脑识别与记忆功能”，则表明软件内置的方案可以在您的设备上挂载功能所需的端口，您可以继续使用。

* 保存设备：
>1. 将手机与目标windows设备通过数据线连接，在弹出的 USB 选择窗口中应用所需模式与 ADB 状态。
>2. 请确认windows后端正在运行。（在任务管理器中搜索 USBManagerWinBackEnd 进程）
>3. 进入手机软件“电脑识别与记忆”页面，点击“允许一台新电脑配对”。应用会立即进入配对，请保持数据线连接。
>4. 配对成功，将会弹出名称和配置窗口，可自行填写或修改。

* 连接到电脑：
>1. windows后端正在运行的前提下，手机与目标设备通过数据线连接，手机会自动验证电脑身份，并自动应用之前保存的配置。
>2. 若windows后端未运行，或手机未保存该设备，或鉴权失败，将正常弹出 USB 选择窗口。

### 特别提醒
当前主要适配目标是 AOSP、Google 原生及类原生系统。厂商可能修改或限制系统 USB 功能，应用内检测结果仅供参考，无法保证功能的稳定性。

本方案不修改手机内核。

若您不需要此实验性功能，可以不授予本应用 root 权限，这不会影响基础功能的正常运行。

当前方案中鉴权端口即开即用、用完即关，端口的准备和开启时间较长，若启用本功能，从插入数据线到弹出窗口或正常应用配置的时间将显著增大，最长可达30秒。若您不接受，关闭此实验性功能即可。

此实验性功能需要配套的 **[USBManagerWinBackEnd](https://github.com/TigerSpirit217/USBManagerWinBackEnd)** Windows 后端，其程序、说明和发布文件位于对应项目。

-----

</details>

## 功能

* **自动检测连接**：自动识别手机以设备模式连接电脑的事件。
* **连接时选择模式**：支持仅充电、文件传输（MTP）、图片传输（PTP）、USB 网络共享（RNDIS）和 MIDI。
* **ADB 一键开关**：在选择窗口中决定本次是否启用 USB 调试。
* **OTG 不弹窗**：手机作为 USB 主机连接 U 盘、键鼠等设备时交给系统原生处理。
* **拔线自动关闭 ADB**：可在设置中关闭；启用时，拔出数据线后自动关闭 USB 调试。
* **锁屏延迟弹窗**：默认等待解锁后显示选择窗口，也可允许锁屏时弹出。

## 安装

### 前置条件

* 已解锁 Bootloader 并取得 root 的 Android 设备。

* 已安装 **LSPosed** 框架。

* Android 11 或更高版本，推荐 Android 12+。

### 步骤

1. 从 [Releases](../../releases) 下载最新 APK。
2. 安装 APK。
3. 在 **LSPosed Manager → 模块**中启用 **USBManager**。
4. 作用域勾选 system（系统框架）。
5. 重启设备。
6. 打开 USBManager，确认模块检测显示正常。

## 使用

1. 插入连接电脑的数据线。
2. 在 USB 选择窗口中选择模式和 ADB 状态。
3. 点击「确定」应用。

默认 USB 模式为**仅充电**，默认关闭 USB 调试；拔线自动关闭 ADB 默认开启，锁屏弹窗默认关闭。这些选项均可在应用主页修改。

## 构建

    git clone https://github.com/TigerSpirit217/USBManager.git
    cd USBManager
    ./gradlew :app:assembleRelease

## 调试

可在 LSPosed Manager 的日志页面搜索 USBManager，或使用：

    adb logcat -s USBManager

主要日志标记：

* **[WATCHER]**：USB 连接和选择流程。
* **[AUTH]**：电脑识别流程。
* **[RX]**：系统广播。
* **[HOOK]**：模块加载。
* **[CLIENT]**：应用与系统模块通信。
* **[CONTROLLER]**：USB 模式和 ADB 应用。

## 许可证

Copyright © TigerSpirit217 · Mulan PubL v2

本项目使用木兰公共许可证，第 2 版（Mulan PubL v2）。完整授权见 [LICENSE](https://license.coscl.org.cn/MulanPubL-2.0)。

## 源码与发布

* 源码仓库：<https://github.com/TigerSpirit217/USBManager>
* 发布页面：<https://github.com/TigerSpirit217/USBManager/releases>
* 问题反馈：<https://github.com/TigerSpirit217/USBManager/issues>
