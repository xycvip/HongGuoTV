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

# ---------------------------------------------------------------------------
# quickjs-kt 的 libquickjs.so 用「字面量字符串」做 JNI 查找，R8 改名字段即崩溃
#
# 反查 libquickjs.so 的符号表，原生侧固定依赖以下名字：
#   GetFieldID(kotlin/UByteArray,        "storage",      "[B")
#   GetFieldID(com.dokar.quickjs.binding.JsFunction,  "name"/"isAsync")
#   GetFieldID(com.dokar.quickjs.binding.JsProperty,  "name"/"writable"/"enumerable"/"configurable")
#   以及 com.dokar.quickjs.{QuickJs,QuickJsException,MemoryUsage} 的类名
#
# AAR 自带的 proguard.txt 里，后面几个类都是 `-keep ... class X { *; }`（含成员），
# 唯独 kotlin.UByteArray 只写了 `-keep,allowoptimization class kotlin.UByteArray`——没有成员，
# 于是 R8 把 storage 改名成 d，GetFieldID 返回 NULL，GetObjectField 触发 JNI abort：
#   kt_ubyte_array_to_js_uint8array ← jobject_to_js_value ← jni_invoke_function
# debug 包不混淆所以正常；release 包只要走到 host.utf8Encode / base64Decode / randomBytes
# 这类返回 UByteArray 的分支就会当场 abort，表现为首页显示几秒后闪退。
# ---------------------------------------------------------------------------
-keep class kotlin.UByteArray { *; }
