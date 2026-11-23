# nj-dialer R8 rules. This project has no git safety net: verify release builds.

# JavaScript bridges: methods are called by name from WebView/Gecko content.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.example.util.WebAppJsBridge { *; }

# GeckoView is driven heavily through JNI, reflection and runtime class loading.
-keep class org.mozilla.geckoview.** { *; }
-keep interface org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**

# yt-dlp's Android wrapper loads a bundled Python/FFmpeg runtime and is accessed
# across worker threads and private processes; keep its public surface intact.
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

# Room's generated implementation is resolved by name at runtime.
-keep class * extends androidx.room.RoomDatabase { void <init>(); }

# Enums persisted by name (Room stores CallType as TEXT).
-keep class com.example.data.db.CallType { *; }
-keepclassmembers enum com.example.data.db.CallType { *; }

# Platform contracts.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keep class top.niunaijun.blackbox.** { *; }
-keep class top.niunaijun.jnihook.** { *; }
-keep class top.niunaijun.blackreflection.** { *; }
-keep class mirror.** { *; }
-keep @top.niunaijun.blackreflection.annotation.BClass class * { *; }
-keep @top.niunaijun.blackreflection.annotation.BClassName class * { *; }
-keep @top.niunaijun.blackreflection.annotation.BClassNameNotProcess class * { *; }
-keepclasseswithmembernames class * {
    @top.niunaijun.blackreflection.annotation.BField.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldSetNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldCheckNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BMethod.* <methods>;
    @top.niunaijun.blackreflection.annotation.BStaticField.* <methods>;
    @top.niunaijun.blackreflection.annotation.BStaticMethod.* <methods>;
    @top.niunaijun.blackreflection.annotation.BMethodCheckNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BConstructor.* <methods>;
    @top.niunaijun.blackreflection.annotation.BConstructorNotProcess.* <methods>;
}
