# USBManager — Android USB 管理模块

[![Stars](https://img.shields.io/github/stars/TigerSpirit217/USBManager?label=Stars&logo=github)](https://github.com/TigerSpirit217/USBManager)
[![GitHub Downloads](https://img.shields.io/github/downloads/Xposed-Modules-Repo/com.tiger.usbmanager/total?label=Downloads)](https://github.com/Xposed-Modules-Repo/com.tiger.usbmanager/releases)
[![License](https://img.shields.io/badge/license-Mulan%20PubL%20v2-blue)](https://license.coscl.org.cn/MulanPubL-2.0)
[![KernelSU](https://img.shields.io/badge/Root-KernelSU-orange.svg)](https://kernelsu.org)
[![LSPosed](https://img.shields.io/badge/LSPosed-API%20101%2B-purple.svg)](https://github.com/LSPosed/LSPosed)

中文 | [English](README_ENG.md)

一个基于 **LSPosed** 框架的 Android 系统模块。手机通过数据线连接电脑时，它会显示 USB 选择窗口，让用户决定本次连接的 USB 模式和 ADB 状态。

### 本模块100%开源。源代码仓库链接见底部。

<table>
  <tr>
    <td width=55%>
      <img width=100% alt=connection src=https://github.com/user-attachments/assets/c52a89a6-349f-40e6-9c2e-04f26d19add5 />
    </td>
    <td width=45%>
      <img width=100% alt=mainpage src=https://github.com/user-attachments/assets/835f210d-d1c3-4d1a-8abe-21eccf3b8f5b />
    </td>
  </tr>
</table>

-----

<details>
<summary><h2>实验性功能（点击展开）</h2></summary>

>“电脑识别与记忆”默认关闭。主应用只提供方案导入、调用和管理界面，设备支持检测、USB 接口、鉴权算法及配套 Windows 后端均由方案提供。
>
>1. 从可信作者处取得方案 ZIP，在“电脑识别与记忆”页面导入，检查作者、版本及 root 执行提示。
>2. 授予 root 权限，运行当前方案的“检测此设备”。是否需要数据线或配套电脑程序，以该方案的说明为准。
>3. 检测通过后手动启用识别，使用该方案配套的 Windows 程序，在手机打开首次配对窗口。
>4. 配对成功后可编辑电脑名称、USB 模式和 ADB 配置；以后插线按方案的鉴权结果自动应用配置。未知电脑、失败或超时仍进入正常 USB 选择流程。
>
>用户可以自己编写方案和 Windows 后端。固定的只有应用与方案包之间的控制接口，手机与 Windows 的通信协议由作者决定。两套旧内置方案、它们的鉴权运行文件、设备检测及原有 Windows 后端已移入 **[额外识别方案项目](https://github.com/TigerSpirit217/USBManagerRecognition)**，主应用不再自动选择或运行任何内置识别方案。
>
>导入、更新、切换方案或固件变更后需重新检测并启用。移除方案会关闭识别并要求方案恢复 USB 状态，保留其电脑记录。两套参考方案会迁移旧版电脑记录。运行效果取决于方案和设备，不能用导入成功代替真实支持检测。
>
>不使用此功能无需授予本应用 root 权限，基础 USB 管理不受影响。

-----
</details>

## 功能

* **自动检测连接**：自动识别手机以设备模式连接电脑的事件。
* **连接时选择模式**：支持仅充电、文件传输（MTP）、图片传输（PTP）、USB 网络共享（RNDIS）和 MIDI，自动适配横屏和竖屏。
* **ADB 一键开关**：在选择窗口中决定本次是否启用 USB 调试。
* **OTG 不弹窗**：手机作为 USB 主机连接 U 盘、键鼠等设备时交给系统原生处理。
* **拔线自动关闭 ADB**：可在设置中关闭；启用时，拔出数据线后自动关闭 USB 调试。
* **锁屏延迟弹窗**：默认等待解锁后显示选择窗口，也可允许锁屏时弹出。
* **游戏免打扰**：默认关闭，可指定应用在前台时跳过选择窗口，执行“仅充电（推荐）”或“使用默认配置”。
* **实时状态页**：底栏切换“主页 / 状态 / 设置”；状态页显示当前连接、USB 模式与 ADB 状态，可直接修改并等待系统确认。

## 安装

### 前置条件

* 已解锁 Bootloader 并取得 root 的 Android 设备。

* 已安装支持现代 Xposed API 101 或更高版本的 **LSPosed** 兼容框架。

* APK 最低要求 Android 8.0（API 26）；设备能否运行模块还取决于框架和系统 USB 实现的兼容性。

### 步骤

1. 从 [Releases](https://github.com/TigerSpirit217/USBManager/releases) 下载最新 APK。
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
