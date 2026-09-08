# USBManager

USBManager 是一个由 **KernelSU 后台模块**和**无桌面图标的弹窗 APK**组成的 USB 模式选择器。连接电脑时，设备先保持仅充电；解锁后弹出一次选择窗口，由用户决定本次连接使用仅充电、MTP 文件传输或 USB 网络共享，并可同时开启 ADB 调试。

[下载最新 KernelSU 安装包](https://github.com/QWEOVO123/USBManager/releases/latest)

## 工作方式

1. `post-fs-data.sh` 在开机早期关闭 ADB，并把 USB 功能恢复为仅充电。
2. `service.sh` 常驻监听 USB 电源、USB role 和 gadget 状态，排除 OTG/Host 模式以及 DCP、HVDCP 等纯充电器。
3. 连接电脑后，模块立即保持仅充电并关闭 ADB。设备已解锁且屏幕点亮时，模块通过 ActivityManager 启动选择窗口；锁屏或息屏时不会弹窗。
4. APK 把本次选择和会话 Token 写入应用专属命令文件。Root 服务校验 Token 后，使用系统 USB 服务切换功能并按复选框状态启停 ADB。
5. 拔线、取消或关闭窗口会恢复仅充电并关闭 ADB。选择只对当前连接有效，不识别电脑、不读取 ADB Key，也不保存设备记录。
6. 弹窗底部的“更多选项”可以进入设备维护菜单，经二次确认后重启到系统、Recovery、Bootloader 或 Fastbootd。

APK 只负责显示窗口和提交选择，Root 操作全部由 KernelSU 脚本执行。APK 没有 Launcher 入口，因此不会出现在桌面或应用抽屉中。

## 安装与卸载

发布包已经包含 APK，不需要单独安装：

1. 下载 `USBManager-KernelSU-v16.zip`。
2. 在 KernelSU 管理器中选择“模块 → 从本地安装”，选择该 ZIP。
3. 安装完成后重启设备。

安装时，`customize.sh` 会从 ZIP 根目录执行 `pm install -r usbmanager.apk`。升级时直接安装新版模块即可。删除模块后，`uninstall.sh` 会自动卸载 `com.tiger.usbmanager` 并清理 `/data/adb/usbmanager`。

模块包结构如下：

```text
USBManager-KernelSU-v16.zip
├── module.prop
├── customize.sh
├── post-fs-data.sh
├── service.sh
├── common.sh
├── usbmanagerctl.sh
├── uninstall.sh
└── usbmanager.apk
```

这些文件必须直接位于 ZIP 根目录，不能在外面再套一层文件夹，否则 KernelSU 无法把它识别为模块。

## 本地构建

构建环境需要 JDK 21、Android SDK 和 Python 3。打包器会先调用 Gradle 构建 Debug APK，再把 APK 和 `kernelsu/module` 中的脚本一起写入可刷入的 ZIP，并为 shell 脚本记录 Unix 可执行权限。

Windows：

```powershell
.\kernelsu\build-module.ps1
```

Linux 或 macOS：

```bash
python3 kernelsu/package_module.py
```

输出文件：

- `app/build/outputs/apk/debug/app-debug.apk`
- `kernelsu/USBManager-KernelSU-v16.zip`

每次推送和 Pull Request 都会由 GitHub Actions 构建模块并保存 workflow artifact；发布 GitHub Release 时，构建出的 ZIP 会自动附加到该 Release。
