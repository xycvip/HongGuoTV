// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException

data class StreamInfo(
    val url: String,
    val key: ByteArray?,
    val quality: String,
    val mimeType: String = "video/mp4",
    /** P-only strict MP4 validation for sources whose encryption metadata was omitted. */
    val requirePlainMp4Validation: Boolean = false
)
class UnplayableVideoException(message: String): IOException(message)
class UnsupportedVideoFormatException(message: String): IOException(message)
class EmptyPlaybackResponseException: IOException("播放接口暂时返回空响应，请稍后重试")
