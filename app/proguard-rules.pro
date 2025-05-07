# 保留 ReqSourceAdd 类及其所有成员，包括构造函数、字段和合成方法
-keep,allowobfuscation class com.horsenma.yourtv.data.ReqSourceAdd {
    *;
    <init>(...);
}

# 保留所有 data class 的字段和方法
-keep class com.horsenma.yourtv.data.** {
    *;
    <init>(...);
}

# 保留 Gson 相关类和反射元数据
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# 确保 Kotlin data class 的合成方法不被移除
-keepclassmembers class com.horsenma.yourtv.data.** {
    *** component*();
    *** copy(...);
}

# 保留其他 Gson 序列化类（可选）
-keep class com.horsenma.yourtv.data.ReqSources { *; }
-keep class com.horsenma.yourtv.data.Source { *; }
-keep class com.horsenma.yourtv.data.TV { *; }
-keep class com.horsenma.yourtv.data.EPG { *; }

# 现有规则（保留以确保其他功能正常）
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
-keep class android.util.Log {
    public static int e(...);
}
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
-keep class com.horsenma.yourtv.decoder.** { *; }
-dontwarn com.horsenma.yourtv.decoder.**
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**
-keep class com.horsenma.yourtv.databinding.** { *; }