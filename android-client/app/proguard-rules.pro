# BluLink ProGuard Rules

# P2-2: Release 构建剥离调试日志（Log.d / Log.v）
# 保留 Log.e / Log.w / Log.i 用于生产环境错误诊断
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# 保留 BuildConfig
-keep class com.bluelink.transfer.BuildConfig { *; }

# Kotlin 元数据
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
