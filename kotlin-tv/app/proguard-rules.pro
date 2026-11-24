-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**

# ---------------------------------------------------------------------------
# 发布包（R8 + 资源收缩）稳定性规则
# 业务层通过反射、类名拼接与 JSON 字段访问的路径较多，整体保留符号，
# 避免收缩后出现「装得上、打不开」的隐性崩溃。
# ---------------------------------------------------------------------------
-keep class com.hongguotv.** { *; }
-keep interface com.hongguotv.** { *; }

# Media3 内核用反射装配解码器 / 渲染器 / 音视频扩展
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coil 通过反射读取 ImageLoader 配置与 fetcher
-keep class coil.** { *; }
-dontwarn coil.**

# OkHttp / Okio 在部分机型上依赖可选的 Conscrypt、BouncyCastle 实现
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# 保留枚举的 values()/valueOf()，播放清晰度、榜单类型等靠名称持久化
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
