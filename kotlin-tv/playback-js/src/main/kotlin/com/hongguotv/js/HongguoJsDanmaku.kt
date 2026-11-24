// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import com.hongguotv.core.DanmakuWindow
import com.hongguotv.core.DanmakuWindowSource
import java.io.IOException

/**
 * 弹幕窗口的热更新 JS 数据源（DanmakuLoader 的数据入口）。
 *
 * 官方弹幕接口的请求、签名、30 秒切窗都在 main.js 内完成；本类只把
 * danmaku 导出的页面映射成 DanmakuLoader 需要的窗口模型。失败直接抛给
 * Loader，由它的退避逻辑决定重试或放弃——弹幕是装饰，不能打断播放。
 */
class HongguoJsDanmaku(
    private val js: HongguoJs,
) : DanmakuWindowSource {
    override fun window(seriesId: String, vid: String, durationMs: Long, startMs: Long): DanmakuWindow {
        val result = runBlockingCall("danmaku", seriesId, vid, startMs.toInt(), durationMs.toInt())
        if (!result.ok) {
            throw IOException(result.error ?: "弹幕脚本调用失败（${result.code}）")
        }
        return HongguoJsContent.danmakuWindowOf(org.json.JSONObject(result.data ?: "{}"), startMs)
    }

    private fun runBlockingCall(function: String, vararg args: Any?): JsCallResult =
        kotlinx.coroutines.runBlocking { js.call(function, *args) }
}
