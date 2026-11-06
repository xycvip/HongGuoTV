// Upstream: anilbeesetti/nextlib v1.11.1-0.17.0
// License: GPL-3.0; see kotlin-tv/third_party/nextlib/LICENSE
package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.LibraryLoader;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Configures and queries the underlying native library. */
@UnstableApi
public final class FfmpegLibrary {

  static {
    MediaLibraryInfo.registerModule("media3.decoder.ffmpeg");
  }

  private static final String TAG = "FfmpegLibrary";

  // Set before the first isAvailable()/renderer initialization when using a downloaded runtime.
  private static volatile java.io.File runtimeLibraryDirectory;

  private static final LibraryLoader LOADER =
      new LibraryLoader("media3ext") {
        @Override
        protected void loadLibrary(String name) {
          java.io.File directory = runtimeLibraryDirectory;
          if (directory == null) {
            System.loadLibrary(name);
            return;
          }
          // Resolve DT_NEEDED dependencies before the JNI bridge. The FFmpeg
          // libraries are built with these SONAMEs by the pinned NextLib build.
          String[] dependencies = {"avutil", "swresample", "swscale", "avcodec"};
          for (String dependency : dependencies) {
            java.io.File library = new java.io.File(directory, "lib" + dependency + ".so");
            if (!library.isFile()) {
              throw new UnsatisfiedLinkError("Missing FFmpeg dependency: " + library);
            }
            System.load(library.getAbsolutePath());
          }
          java.io.File bridge = new java.io.File(directory, "lib" + name + ".so");
          if (!bridge.isFile()) {
            throw new UnsatisfiedLinkError("Missing FFmpeg JNI bridge: " + bridge);
          }
          System.load(bridge.getAbsolutePath());
        }
      };

  private static @MonotonicNonNull String version;
  private static int inputBufferPaddingSize = C.LENGTH_UNSET;

  private FfmpegLibrary() {}

  /**
   * Override the names of the FFmpeg native libraries. If an application wishes to call this
   * method, it must do so before calling any other method defined by this class, and before
   * instantiating a {@link FfmpegAudioRenderer} instance.
   *
   * @param libraries The names of the FFmpeg native libraries.
   */
  public static void setLibraries(String... libraries) {
    LOADER.setLibraries(libraries);
  }

  /**
   * Configures an application-private directory containing the verified FFmpeg
   * shared libraries. Must be called before isAvailable() or renderer creation.
   * Pass null to use the normal APK jniLibs lookup.
   */
  public static void setRuntimeLibraryDirectory(@Nullable java.io.File directory) {
    if (version != null || LOADER.isAvailable()) {
      throw new IllegalStateException("FFmpeg libraries have already been initialized");
    }
    runtimeLibraryDirectory = directory;
  }

  /** Returns whether the underlying library is available, loading it if necessary. */
  public static boolean isAvailable() {
    return LOADER.isAvailable();
  }

  /** Returns the version of the underlying library if available, or null otherwise. */
  @Nullable
  public static String getVersion() {
    if (!isAvailable()) {
      return null;
    }
    if (version == null) {
      version = ffmpegGetVersion();
    }
    return version;
  }

  /**
   * Returns the required amount of padding for input buffers in bytes, or {@link C#LENGTH_UNSET} if
   * the underlying library is not available.
   */
  public static int getInputBufferPaddingSize() {
    if (!isAvailable()) {
      return C.LENGTH_UNSET;
    }
    if (inputBufferPaddingSize == C.LENGTH_UNSET) {
      inputBufferPaddingSize = ffmpegGetInputBufferPaddingSize();
    }
    return inputBufferPaddingSize;
  }

  /**
   * Returns whether the underlying library supports the specified MIME type.
   *
   * @param mimeType The MIME type to check.
   */
  public static boolean supportsFormat(String mimeType) {
    if (!isAvailable()) {
      return false;
    }
    @Nullable String codecName = getCodecName(mimeType);
    if (codecName == null) {
      return false;
    }
    if (!ffmpegHasDecoder(codecName)) {
      Log.w(TAG, "No " + codecName + " decoder available. Check the FFmpeg build configuration.");
      return false;
    }
    return true;
  }

  /**
   * Returns the name of the FFmpeg decoder that could be used to decode the format, or {@code null}
   * if it's unsupported.
   */
  @Nullable
  /* package */ static String getCodecName(String mimeType) {
    return switch (mimeType) {
      // Audio codecs
      case MimeTypes.AUDIO_AAC -> "aac";
      case MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 -> "mp3";
      case MimeTypes.AUDIO_AC3 -> "ac3";
      case MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3";
      case MimeTypes.AUDIO_TRUEHD -> "truehd";
      case MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "dca";
      case MimeTypes.AUDIO_VORBIS -> "vorbis";
      case MimeTypes.AUDIO_OPUS -> "opus";
      case MimeTypes.AUDIO_AMR_NB -> "amrnb";
      case MimeTypes.AUDIO_AMR_WB -> "amrwb";
      case MimeTypes.AUDIO_FLAC -> "flac";
      case MimeTypes.AUDIO_ALAC -> "alac";
      case MimeTypes.AUDIO_MLAW -> "pcm_mulaw";
      case MimeTypes.AUDIO_ALAW -> "pcm_alaw";

      // Video codecs
      case MimeTypes.VIDEO_H264 -> "h264";
      case MimeTypes.VIDEO_H265 -> "hevc";
      case MimeTypes.VIDEO_AV1 -> "libdav1d";
      case MimeTypes.VIDEO_MPEG -> "mpegvideo";
      case MimeTypes.VIDEO_MPEG2 -> "mpeg2video";
      case MimeTypes.VIDEO_VP8 -> "vp8";
      case MimeTypes.VIDEO_VP9 -> "vp9";
      default -> null;
    };
  }

  private static native String ffmpegGetVersion();

  private static native int ffmpegGetInputBufferPaddingSize();

  private static native boolean ffmpegHasDecoder(String codecName);
}
