// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Phoenix 线路采用 0.0.42 Native 的完整播放实现。
 * 设备注册、会话、请求签名、播放模型解析及媒体地址处理均由
 * PhoenixPlaybackRepository 承担；不再保留旧 Phoenix 专用请求协议。
 */
class PhoenixPlaybackSource(
    private val http: OkHttpClient,
    private val onEvent: (String) -> Unit = {}
) : StreamSource {
    override val name: String = "phoenix"

    private val repository by lazy {
        PhoenixPlaybackRepository(
            http = http,
            clock = System::currentTimeMillis,
            playbackSession = PhoenixPlaybackDeviceSession.shared,
            signer = PhoenixRequestSigner(),
            site = "https://hongguoduanju.com",
            requestText = ::requestText
        )
    }

    override fun stream(id: String, maxQuality: Int): StreamInfo {
        onEvent("Phoenix：开始获取播放地址")
        return try {
            repository.stream(id, maxQuality).also {
                onEvent("Phoenix：播放地址获取成功，清晰度=${it.quality}")
            }
        } catch (error: Exception) {
            onEvent("Phoenix：播放地址获取失败，异常类型=${error.javaClass.simpleName}")
            throw error
        }
    }

    private fun requestText(url: String, signed: PhoenixRequestSigner.Request?): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", VendorConstants.VIDEO_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (key, value) -> builder.header(key, value) }
        signed?.body?.let {
            builder.post(it.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        return RequestScope.execute(http.newCall(builder.build())) { response ->
            if (!response.isSuccessful) {
                throw IOException("内容请求失败（HTTP ${response.code}）")
            }
            val body = response.body ?: throw IOException("内容响应为空")
            val limit = 16L * 1024 * 1024
            if (body.contentLength() > limit) throw IOException("内容响应过大")
            val output = ByteArrayOutputStream()
            val input = body.byteStream()
            val buffer = ByteArray(8192)
            while (output.size() <= limit) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            if (bytes.size > limit) throw IOException("内容响应过大")
            if (bytes.isEmpty()) {
                throw IOException("接口响应为空（HTTP ${response.code}，响应字节数=0），请稍后重试")
            }
            String(bytes, Charsets.UTF_8)
        }
    }
}