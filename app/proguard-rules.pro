# Preserve generic signatures and annotations for Gson TypeToken reflection
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Gson rules
-dontwarn sun.misc.**
-dontwarn com.google.gson.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep models & data classes used in Gson serialization
-keep class io.github.jh_mmm.biliaccelerator.core.RewriteResult { *; }
-keep class io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig { *; }
-keep class io.github.jh_mmm.biliaccelerator.core.RewriteLogEntry { *; }
-keep class io.github.jh_mmm.biliaccelerator.core.StatsSnapshot { *; }
-keep class io.github.jh_mmm.biliaccelerator.provider.RewriteResultDto { *; }
-keep class io.github.jh_mmm.biliaccelerator.btr.** { *; }

# Keep LibXposed API 101 entry point and module rules
-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class io.github.jh_mmm.biliaccelerator.hook.HookEntry { *; }
-adaptresourcefilecontents META-INF/xposed/java_init.list
-dontwarn io.github.libxposed.**

# Keep methods invoked via reflection in HookEntry self-check
-keep class io.github.jh_mmm.biliaccelerator.core.StatsManager {
    public static void markFrameworkSelfInjected();
}
-keep class io.github.jh_mmm.biliaccelerator.ui.MainActivity {
    public static boolean isModuleActive();
}
-keepclassmembers class io.github.jh_mmm.biliaccelerator.ui.MainActivity$Companion {
    public boolean isModuleActive();
}

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
