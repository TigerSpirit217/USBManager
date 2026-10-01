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
# libxposed API 是 compileOnly，运行时由 framework 提供；无需保留 service 整包。
-dontwarn io.github.libxposed.api.**

# 模块入口，由 assets/xposed_init 与 META-INF/xposed/java_init.list 反射加载。
-keep,allowoptimization class com.tiger.usbmanager.UsbManagerModule {
    public <init>();
    public void onModuleLoaded(io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam);
    public void onSystemServerStarting(io.github.libxposed.api.XposedModuleInterface$SystemServerStartingParam);
}

# system_server 侧 hook 包：UsbManagerModule 通过
#   Class.forName("com.tiger.usbmanager.hook.SystemServerHooks")
#   访问其单例 INSTANCE 字段，并反射调用 install(...) 重载。
# 仅保留反射契约，其他实现方法仍可裁剪、混淆和优化。
-keep,allowoptimization class com.tiger.usbmanager.hook.SystemServerHooks {
    public static final com.tiger.usbmanager.hook.SystemServerHooks INSTANCE;
    public void install(...);
}

# Launched by app_process from the root helper; native entry points use fixed JNI names.
-keep,allowoptimization class com.tiger.usbmanager.auth.UsbAuthDaemon {
    public static void main(java.lang.String[]);
    native <methods>;
}
# 默认 proguard-android-optimize.txt 已有通用 JNI 规则。

# ----------------------------------------------------------------------
# Gson（跨进程 pending-apply 载荷）
# ----------------------------------------------------------------------
# Gson 依赖字段反射，保留泛型签名与注解属性。
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**

# Gson 反射创建模型，允许类名混淆，但字段名必须与已有 JSON 一致。
# 不再保留未使用的 copy/component/toString 等 data class 方法。
-keep,allowobfuscation class com.tiger.usbmanager.bridge.PendingApplyPayload {
    <init>(...);
}
# Gson 会反射写入字段，不能允许 R8 对这些字段进行常量传播等优化。
-keepclassmembers class com.tiger.usbmanager.bridge.PendingApplyPayload {
    !static !transient <fields>;
}

# UsbController 使用 mode.name 拼接系统 FUNCTION_* 字段，保留枚举常量名称。
# entries/getter/fromWire 的直接调用由 R8 自动追踪，不再整类保留所有方法。
-keep,allowoptimization enum com.tiger.usbmanager.policy.UsbMode {
    public static com.tiger.usbmanager.policy.UsbMode *;
}

# ----------------------------------------------------------------------
# 其余（AndroidX / Kotlin / Gson 自身规则）由各自携带的 consumer rules
# 自动合并，无需在此重复。
# ----------------------------------------------------------------------
