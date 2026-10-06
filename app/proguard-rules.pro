# Keep LibXposed API 101 entry point and module rules
-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class io.github.jh_mmm.biliaccelerator.hook.HookEntry { *; }
-adaptresourcefilecontents META-INF/xposed/java_init.list
-dontwarn io.github.libxposed.**

# Keep Application and Receiver components
-keep class io.github.jh_mmm.biliaccelerator.BiliAcceleratorApp { *; }
-keep class io.github.jh_mmm.biliaccelerator.receiver.StatsReceiver { *; }

# Keep module hook implementations
-keep class io.github.jh_mmm.biliaccelerator.hook.** { *; }

# Keep models for Gson serialization & cross-process IPC
-keep class io.github.jh_mmm.biliaccelerator.core.** { *; }

# Keep ContentProvider and UI components
-keep class io.github.jh_mmm.biliaccelerator.provider.StatsProvider { *; }
-keep class io.github.jh_mmm.biliaccelerator.ui.** { *; }

# Keep isModuleActive hooked via reflection in MainActivity
-keepclassmembers class io.github.jh_mmm.biliaccelerator.ui.MainActivity {
    boolean isModuleActive();
}
