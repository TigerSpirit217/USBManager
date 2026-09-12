# ======================================================================
# USBManager — R8 / ProGuard 规则
#
# 本模块是一个 LSPosed 模块：`libxposed` API 由 framework 在运行时提供
# （compileOnly），模块入口与 system_server 侧 hook 包均通过反射加载，
# 跨进程 pending-apply 载荷通过 Gson 反射序列化。以下规则保证混淆后仍能
# 正常运行。
# ======================================================================

# ----------------------------------------------------------------------
# LSPosed / libxposed
# ----------------------------------------------------------------------
# libxposed "api" 是 compileOnly：运行时由 LSPosed framework 提供，R8 看不见，
# 因此需要保留引用并抑制缺失类告警，避免模块入口与 hook 调用链被错误裁剪。
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.** { *; }

# 模块入口，由 assets/xposed_init 与 META-INF/xposed/java_init.list 反射加载。
-keep class com.tiger.usbmanager.UsbManagerModule { *; }

# system_server 侧 hook 包：UsbManagerModule 通过
#   Class.forName("com.tiger.usbmanager.hook.SystemServerHooks")
#   访问其单例 INSTANCE 字段，并反射调用 install(...) 重载。
# 必须整体保留（含 INSTANCE 字段与 install 方法）。
-keep class com.tiger.usbmanager.hook.SystemServerHooks { *; }

# ----------------------------------------------------------------------
# Gson（跨进程 pending-apply 载荷）
# ----------------------------------------------------------------------
# Gson 依赖字段反射，保留泛型签名与注解属性。
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**

# 跨进程 pending-apply 载荷，经 Gson toJson/fromJson 序列化，字段名不可混淆。
-keep class com.tiger.usbmanager.bridge.PendingApplyPayload { *; }

# USB 模式枚举：多处经 `UsbMode.entries`（Kotlin 枚举的 entries 属性）遍历，
# 混淆/优化可能移除其合成访问器或常量，故整体保留。
-keep class com.tiger.usbmanager.policy.UsbMode { *; }

# ----------------------------------------------------------------------
# 其余（AndroidX / Kotlin / Gson 自身规则）由各自携带的 consumer rules
# 自动合并，无需在此重复。
# ----------------------------------------------------------------------