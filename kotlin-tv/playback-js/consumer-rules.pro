# quickjs-kt 通过 Kotlin DSL 绑定，业务代码不使用反射；
# 但 libquickjs.so 内部是按「字面量字段名」做 JNI 查找的，`kotlin.UByteArray` 的 storage
# 一旦被 R8 改名就会 JNI abort（完整说明见 app/proguard-rules.pro 末尾）。
# 该规则同时写在 app 模块里（应用构建实际生效的那份），此处作为随库分发的兜底。
-keep class kotlin.UByteArray { *; }
