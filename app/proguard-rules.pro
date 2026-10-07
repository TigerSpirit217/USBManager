# libxposed's API is supplied by LSPosed at runtime (compileOnly).
-dontwarn io.github.libxposed.api.**

# Named in assets/xposed_init and META-INF/xposed/java_init.list.
# Keep the entry point and framework callbacks; other members may be optimized.
-keep,allowoptimization class com.tiger.usbmanager.UsbManagerModule {
    public <init>();
    public void onModuleLoaded(io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam);
    public void onSystemServerStarting(io.github.libxposed.api.XposedModuleInterface$SystemServerStartingParam);
}

# UsbManagerModule resolves this singleton and its install overloads by reflection.
-keep,allowoptimization class com.tiger.usbmanager.hook.SystemServerHooks {
    public static final com.tiger.usbmanager.hook.SystemServerHooks INSTANCE;
    public void install(...);
}

# PendingApplyPayload uses explicit JSON keys, and USB constants use wire values.
# Neither requires reflective field names, enum names, or whole-package keep rules.
# Android manifest entry points and library contracts use generated/consumer rules.
