# libplayer.so resolves JNI methods by their exact Java class and method names.
-keep class is.xyz.mpv.MPVLib { *; }
-keepclasseswithmembernames class * { native <methods>; }
