-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**

# NextLib / FFmpeg JNI: keep native method names and decoder output buffer fields.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class androidx.media3.decoder.VideoDecoderOutputBuffer {
    *;
}
