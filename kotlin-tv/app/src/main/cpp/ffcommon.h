// Upstream: anilbeesetti/nextlib v1.11.1-0.17.0
// License: GPL-3.0; see kotlin-tv/third_party/nextlib/LICENSE
#ifndef NEXTPLAYER_FFCOMMON_H
#define NEXTPLAYER_FFCOMMON_H

#include <jni.h>
#include <android/log.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include "libswresample/swresample.h"
};

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define ERROR_STRING_BUFFER_LENGTH 256


/**
 * Releases the specified context.
 */
void releaseContext(AVCodecContext *context);

/**
* Returns the AVCodec with the specified name, or NULL if it is not available.
*/
AVCodec *getCodecByName(JNIEnv *env, jstring codecName);

/**
 * Outputs a log message describing the avcodec error number.
 */
void logError(const char *functionName, int errorNumber);

#endif //NEXTPLAYER_FFCOMMON_H
