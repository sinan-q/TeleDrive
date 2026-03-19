# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# ErrorProne annotations are used for static analysis and are not needed at runtime.
-dontwarn com.google.errorprone.annotations.**

# TDLib (Telegram Database Library) ProGuard Rules
# We must keep TdApi classes and their members because they are accessed via JNI.
# If R8 renames or removes them, the native library (tdjni) will crash the app.
-keep class org.drinkless.tdlib.TdApi { *; }
-keep class org.drinkless.tdlib.TdApi$** { *; }
-keep class org.drinkless.tdlib.Client { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Hilt/Dagger rules (usually handled by the plugin, but added for safety)
-keep class dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Room rules (usually handled by the plugin)
-dontwarn androidx.room.paging.**

# Keep Kotlinx Serialization if used with reflection (though usually not needed with the plugin)
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, SourceFile, LineNumberTable
