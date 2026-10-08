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
-keep class io.github.jh_mmm.biliaccelerator.core.** { *; }
-keepclassmembers class io.github.jh_mmm.biliaccelerator.core.** { *; }
-keep class io.github.jh_mmm.biliaccelerator.hook.HookStatusTracker$* { *; }

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

# Keep ContentProvider and UI components
-keep class io.github.jh_mmm.biliaccelerator.provider.** { *; }
-keep class io.github.jh_mmm.biliaccelerator.ui.** { *; }

# Keep isModuleActive hooked via reflection in MainActivity
-keepclassmembers class io.github.jh_mmm.biliaccelerator.ui.MainActivity {
    boolean isModuleActive();
}

