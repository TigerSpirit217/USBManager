# USBManager — Android USB 智能管理模块

一个基于 **LSPosed** 框架的 Android 系统模块，自动管理 USB 模式与 ADB 开关，根据已识别的电脑记忆配置，实现「即插即用」。

## 功能

- **自动 USB 模式切换**：连接电脑后自动切换到 MTP（文件传输）、RNDIS（网络共享）、MIDI 或仅充电模式
- **智能 ADB 控制**：根据主机自动开启或关闭 USB 调试，拔线可选自动关闭 ADB
- **设备记忆**：记住每台电脑的配置（模式 + ADB + 是否自动应用），下次连接自动生效
- **选择弹窗**：未识别的电脑弹出选择器，让用户决定本次配置和是否记住
- **重枚举保护**：USB 模式切换（如仅充电→MTP）导致设备重新枚举时，不会重复弹窗扰民
- **Root 回退**：当框架 API 不可用时自动尝试 `su` 命令写入系统属性（要求能使用root权限）

## 安装

### 前置条件

- 已解锁 Bootloader 并 Root 的 Android 设备
- 已安装 **LSPosed**（Zygisk 或全局命名空间模式均可）
- Android 12+（推荐）或 11

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
- 编辑名称、USB 模式、ADB 开关、自动应用开关
- 删除主机记录

### 默认设置

- 未知主机默认 USB 模式：**仅充电**
- 未知主机默认 ADB：**关闭**
- 拔线自动关闭 ADB：**开启**
- 首次连接弹出选择器时默认不勾选「记住此电脑」

可通过 USBManager 应用 → 右上角设置图标修改默认值。

## 构建

```bash
# 克隆仓库
git clone https://github.com/your-username/USBManager.git
cd USBManager

# 使用 Gradle 构建
./gradlew :app:assembleRelease

# 构建产物位于 USBManager/app/build/outputs/apk/release/
```

## 调试

### 查看日志

模块日志输出到 `USBManager` tag。可通过 LSPosed Manager 查看：

1. 打开 **LSPosed Manager**
2. 点击右上角菜单 → **日志** → 选择 **USBManager**
3. 查看实时日志

或者使用 ADB Logcat：

```bash
adb logcat -s USBManager
```

关键日志标签：
- `[WATCHER]` — USB 事件处理流程
- `[RX]` — 广播接收器
- `[HOOK]` — Hook 初始化
- `[CLIENT]` — ContentProvider 通信
- `[CONTROLLER]` — USB 模式/ADB 配置应用

## 常见问题

### Q: 模块已激活，但插入 USB 无反应？
A: 检查 LSPosed 作用域是否勾选了 `system`。重启设备后重试。查看 LSPosed 日志确认模块是否正常注入。

### Q: 拔线时会弹窗？
A: 部分 OEM 在 USB 拆除过程中会发送瞬时「已连接」信号，模块已内置 300 ms 去抖机制。如果仍出现，请查看日志确认是否该去抖生效。

### Q: 选择配置后 USB 状态没变？
A: Android 14+ 已将 ADB 从 USB function 字符串中分离，模块通过独立路径设置 ADB。如果系统 API 被限制，模块会尝试 Root 回退（需要 `su` 可用）。

### Q: 修改已保存设备配置后不生效？
A: 模块维护 APP 数据库和 system_server 本地缓存双副本，修改后会自动同步。如果 APP 进程被杀后插入 USB，设备仍会使用最新配置。

## 许可证

本项目使用木兰公共许可证，第 2 版（Mulan PubL v2）。
完整授权见 [LICENSE](https://license.coscl.org.cn/MulanPubL-2.0)。

## 源码与发布
- 源码仓库：https://github.com/TigerSpirit217/USBManager
- 发布页面：https://github.com/TigerSpirit217/USBManager/releases
- 反馈Issues：https://github.com/TigerSpirit217/USBManager/issues