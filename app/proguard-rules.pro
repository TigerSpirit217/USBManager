# Add project specific ProGuard rules here.

# Xposed loads this class by name from META-INF/xposed/java_init.list.
-keep,allowoptimization class com.tiger.usbmanager.UsbManagerModule {
    <init>();
    void onModuleLoaded(io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam);
    void onPackageLoaded(io.github.libxposed.api.XposedModuleInterface$PackageLoadedParam);
}

# Keep bridge contract and policy data classes used across process boundaries via Gson.
-keep class com.tiger.usbmanager.policy.HostInfo { *; }
-keep class com.tiger.usbmanager.policy.UsbMode { *; }
-keepattributes Signature, *Annotation*
