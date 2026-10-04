# Keep Xposed entry and callback methods
-keep class io.github.jh_mmm.biliaccelerator.hook.** { *; }
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**
-dontwarn android.app.AndroidAppHelper

# Keep models for Gson serialization & cross-process IPC
-keep class io.github.jh_mmm.biliaccelerator.core.** { *; }

# Keep ContentProvider and UI components
-keep class io.github.jh_mmm.biliaccelerator.provider.StatsProvider { *; }
-keep class io.github.jh_mmm.biliaccelerator.ui.** { *; }

# Keep isModuleActive hooked via reflection in MainActivity
-keepclassmembers class io.github.jh_mmm.biliaccelerator.ui.MainActivity {
    boolean isModuleActive();
}
