# libplayer.so resolves JNI methods by their exact Java class and method names.
-keep class com.x.hongguotv.libmpv.LibMpvNativeBridge { *; }
-keepclasseswithmembernames class * { native <methods>; }
