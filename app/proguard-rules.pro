# Keep Xposed entry and callback methods
-keep class com.realzza.biliaccelerator.hook.** { *; }
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**
-dontwarn android.app.AndroidAppHelper

# Keep models for Gson serialization & cross-process IPC
-keep class com.realzza.biliaccelerator.core.** { *; }

# Keep ContentProvider and UI components
-keep class com.realzza.biliaccelerator.provider.StatsProvider { *; }
-keep class com.realzza.biliaccelerator.ui.** { *; }

# Keep isModuleActive hooked via reflection in MainActivity
-keepclassmembers class com.realzza.biliaccelerator.ui.MainActivity {
    boolean isModuleActive();
}
