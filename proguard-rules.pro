# ============================================
# AI Code Studio ProGuard 规则
# ============================================
# 注意：AI Code Studio 内置编译器（BuiltinCompiler）当前不启用代码混淆，
#       此文件用于兼容 Android Studio 正式构建时的混淆配置。
# ============================================

# ---- 基本规则 ----
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# ---- 保留 Android 组件 ----
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

# ---- 保留自定义 View ----
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# ---- 保留 native 方法 ----
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---- 保留 JNI 绑定类 ----
-keep class com.aicodestudio.ide.ai.engine.** { *; }
-keep class k2fsa.sherpa.** { *; }

# ---- 保留 llama.cpp JNI 接口 ----
-keep class com.aicodestudio.ide.ai.engine.LlamaEngine { *; }
-keep class com.aicodestudio.ide.ai.engine.KVCacheManager { *; }
-keep class com.aicodestudio.ide.ai.engine.NativeLibraryLoader { *; }

# ---- 保留序列化类 ----
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ---- 保留枚举 ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- 保留 Parcelable ----
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ---- 保留 R 类 ----
-keepclassmembers class **.R$* {
    public static <fields>;
}

# ---- 第三方库：Gson（如果使用）----
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# ---- 第三方库：OkHttp（如果使用）----
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ---- 第三方库：语法高亮 Prism4j ----
-keep class ca.blarg.prism4j.** { *; }

# ---- 第三方库：Markdown 渲染 ----
-keep class io.noties.** { *; }

# ---- 移除日志（正式构建时取消注释）----
# -assumenosideeffects class android.util.Log {
#     public static *** v(...);
#     public static *** d(...);
#     public static *** i(...);
#     public static *** w(...);
#     public static *** e(...);
# }

# ---- 忽略警告 ----
-dontwarn android.support.**
-dontwarn androidx.**
-dontwarn org.eclipse.jdt.**
-dontwarn com.android.tools.**
