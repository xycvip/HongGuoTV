// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * 设备视频解码能力探测。
 *
 * 「无感连播」的双播放器需要同时持有一路主解码 + 一路备用解码。电视盒子的常见坑是
 * MediaCodec 的 maxSupportedInstances 只有 1，或者虽然报 2、实际只有一路能跑：
 * 第二路申请时系统会回收正在播放的那一路，表现就是播到一半
 * ERROR_CODE_DECODING_FAILED（「当前设备无法解码该视频」），而退出播放页
 * （两路实例都销毁）重新进来又完全正常。
 *
 * 所以在创建第二实例之前先问一遍系统；查不到、报 0、或者只有 1，一律按
 * 「不能并发」处理，走同实例 playlist 预载，功能不丢、只是少了首帧预渲染。
 */
internal object DecoderCapacity {

    /** mime → 允许的并发实例数。一台设备同一种编码不会变，缓存住避免每次开播都查。 */
    private val cache = HashMap<String, Int>()

    /** 该视频编码允许同时存在的解码实例数；不是视频 MIME 或查不到时返回 1。 */
    fun maxInstances(mimeType: String?): Int {
        val mime = mimeType?.takeIf { it.startsWith("video/", ignoreCase = true) } ?: return 1
        cache[mime]?.let { return it }
        val value = runCatching { query(mime) }.getOrDefault(1)
        cache[mime] = value
        return value
    }

    /**
     * 能不能安全地再开第二路硬件解码器。
     * 编码类型还没解析出来时（videoFormat 为空）不拦——这时信息不足，
     * 交给运行期的「被抢占就撤」兜底，比一上来就永远禁用双播放器要好。
     */
    fun canRunTwoDecoders(mimeType: String?): Boolean {
        val mime = mimeType?.takeIf { it.startsWith("video/", ignoreCase = true) } ?: return true
        return maxInstances(mime) >= 2
    }

    private fun query(mime: String): Int {
        // 用一个常见规格去问系统「这种编码你会派哪颗解码器」，再读那颗解码器的并发上限。
        val format = MediaFormat.createVideoFormat(mime, 1920, 1080)
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecName = list.findDecoderForFormat(format) ?: return 1
        // 注意：MediaCodecList.getCodecCount()/getCodecInfoAt() 是 @hide，
        // 编译期不可见，必须走公开的 getCodecInfos()。
        val info = list.codecInfos.firstOrNull { it.name == codecName } ?: return 1
        if (info.isEncoder) return 1
        val caps: MediaCodecInfo.CodecCapabilities = info.getCapabilitiesForType(mime)
        // 不少厂商这里固定回 0（未声明），按 1 处理，宁可不并发也别把主画面挤掉。
        return caps.maxSupportedInstances.coerceAtLeast(1)
    }
}
