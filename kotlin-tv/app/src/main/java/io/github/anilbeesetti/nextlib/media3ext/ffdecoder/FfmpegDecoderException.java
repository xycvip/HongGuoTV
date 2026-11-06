// Upstream: anilbeesetti/nextlib v1.11.1-0.17.0
// License: GPL-3.0; see kotlin-tv/third_party/nextlib/LICENSE
package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;

/** Thrown when an FFmpeg decoder error occurs. */
@UnstableApi
public final class FfmpegDecoderException extends DecoderException {

  /* package */ FfmpegDecoderException(String message) {
    super(message);
  }

  /* package */ FfmpegDecoderException(String message, Throwable cause) {
    super(message, cause);
  }
}
