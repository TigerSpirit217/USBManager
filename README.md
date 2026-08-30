# USBManager — Android USB 智能管理模块

一个基于 **LSPosed** 框架的 Android 系统模块，自动管理 USB 模式与 ADB 开关，根据已识别的电脑记忆配置，实现「即插即用」。

## 功能

* **自动 USB 模式切换**：连接电脑后自动切换到 MTP（文件传输）、RNDIS（网络共享）、MIDI 或仅充电模式

* **智能 ADB 控制**：根据主机自动开启或关闭 USB 调试，拔线可选自动关闭 ADB

* **设备记忆**：记住每台电脑的配置（模式 + ADB + 是否自动应用），下次连接自动生效

* **选择弹窗**：未识别的电脑弹出选择器，让用户决定本次配置和是否记住

* **重枚举保护**：USB 模式切换（如仅充电→MTP）导致设备重新枚举时，不会重复弹窗扰民

* **Root 回退**：当框架 API 不可用时自动尝试 `su` 命令写入系统属性（要求能使用root权限）

## 安装

### 前置条件

* 已解锁 Bootloader 并 Root 的 Android 设备

* 已安装 **LSPosed**（Zygisk 或全局命名空间模式均可）

* Android 12+（推荐）或 11

### 步骤

1. 从 [Releases](../../releases) 下载最新 APK
2. 安装 APK 到设备
3. 打开 **LSPosed Manager** → 模块列表 → 勾选 **USBManager**
4. **作用域**：勾选 `system`（系统框架）
5. 重启 System UI 或重启设备
6. 从桌面启动 USBManager 应用，查看使用说明和已保存的主机列表

## 使用

### 首次连接

1. 插入 USB 数据线连接到电脑
2. 弹出选择器，选择 USB 模式（仅充电/文件传输/MIDI/网络共享）和 ADB 开关
3. 勾选「记住此电脑」以保存配置
4. 点击「确定」生效

### 已知主机（已保存）

连接已保存且标记为「自动应用」的电脑 → 静默应用配置，无弹窗。
连接已保存但未标记「自动应用」的电脑 → 弹出选择器，预填已保存的配置供修改。

### 管理主机

打开 USBManager 应用，可查看所有已保存的主机列表，支持：

* 编辑名称、USB 模式、ADB 开关、自动应用开关

* 删除主机记录

### 默认设置

* 未知主机默认 USB 模式：**仅充电**

* 未知主机默认 ADB：**关闭**

* 拔线自动关闭 ADB：**开启**

* 首次连接弹出选择器时默认不勾选「记住此电脑」

可通过 USBManager 应用 → 右上角设置图标修改默认值。

## 安全模型

模块在 system\_server 与模块应用之间通过 `ContentProvider` 与广播交换配置，因此对跨进程调用做了如下防护：

* **调用方 UID 白名单**：`ContentProvider` 与广播接收器仅接受系统进程与模块自身进程的调用，其余来源一律拒绝。UID 无法伪造，是真正的鉴权依据。

* **桥接 Token（纵深防御）**：在 UID 鉴权之上再校验编译期桥接 Token。即使 Token 被反编译提取，第三方应用也因 UID 不匹配而无法滥用（例如伪造广播强制开启 ADB）。

* **选择器调用来源校验**：非可信来源启动 USB 选择器时，不会预先勾选「ADB 开启」，防止钓鱼诱导。

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
3. 查看实时日志

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

## 常见问题

### Q: 模块已激活，但插入 USB 无反应？

A: 检查 LSPosed 作用域是否勾选了 `system`。重启设备后重试。查看 LSPosed 日志确认模块是否正常注入。

### Q: 拔线时会弹窗/闪通知？

A: 部分 OEM 在 USB 拆除或模式切换（如仅充电→文件传输）过程中会发送瞬时「已连接」/「断开」信号。模块内置两段去抖：连接信号 300 ms、断开信号 800 ms，并在「断开→重连」的重枚举序列中自动取消待执行的断开任务，从而避免误弹窗、误关 ADB。若仍出现，请查看 `[WATCHER]` 日志确认去抖是否生效。

### Q: 选择配置后 USB 状态没变？

A: Android 14+ 已将 ADB 从 USB function 字符串中分离，模块通过独立路径设置 ADB。如果系统 API 被限制，模块会尝试 Root 回退（需要 `su` 可用）。

### Q: 修改已保存设备配置后不生效？

A: 模块维护 APP 数据库和 system\_server 本地缓存双副本，修改后会自动同步。如果 APP 进程被杀后插入 USB，设备仍会使用最新配置。

### Q: 拔出数据线后再插入，弹窗不消失 / 再次连接不弹窗且 ADB 仍开着？

A: 这类「短期状态残留」问题请升级到 w/ 修复的版本。修复内容包括：

* **拔线即消失**：拔线时主动向选择器界面发送关闭广播，处理后台/通知路径打开的选择器不会残留。

* **删除配置后不再残留**：删除/清空已保存主机时，会通过 ContentProvider 信号通知 system\_server 清除该主机相关的短期状态（重放缓存与「30 秒内不关 ADB」宽限期），从而避免「删除后立刻拔线，ADB 仍开」或「删除后重连不再弹窗」。

* **Android 13+ 兼容**：修复动态广播接收器缺少 exported 标志导致的注册失败（SDK 33+ 强制要求，否则 `ACTION_CHOOSER_CLOSED` 等广播无法到达 system\_server）。

## 许可证

本项目使用木兰公共许可证，第 2 版（Mulan PubL v2）。
完整授权见 [LICENSE](https://license.coscl.org.cn/MulanPubL-2.0)。

## 源码与发布

* 源码仓库：<https://github.com/TigerSpirit217/USBManager>

* 发布页面：<https://github.com/TigerSpirit217/USBManager/releases>

* 反馈Issues：<https://github.com/TigerSpirit217/USBManager/issues>

