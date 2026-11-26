// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 红果图片 URL 归一化。
 *
 * 线上脚本不同版本下发的头像格式并不统一，而 Android（尤其 TV 盒子）对其中几种
 * 直接解不出来，表现就是「头像空白」：
 *  - 裸 `novel-pic/{hash}`：CDN 原图需要签名，直接 403；
 *  - `~tplv-resize:200:0.heic`：系统没有 HEIC 解码器；
 *  - 协议相对 `//p3-novel...`：Coil 不认。
 *
 * 统一重建成 `~tplv-shrink:640:0.image`（实测返回 image/jpeg，Coil 直接可加载）。
 * 已经是这个格式的原样返回，重复调用不会把后缀叠上去。
 */
object ImageUrls {
    private val NOVEL_PIC = Regex("novel-pic/([A-Za-z0-9_-]+)")
    private const val NOVEL_PIC_BASE = "https://p3-novel.byteimg.com/novel-pic/"
    private const val SHRINK_SUFFIX = "~tplv-shrink:640:0.image"

    fun normalize(raw: String?): String {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return ""
        // 已有后缀时正则只截取 hash，重建结果与原来一致，天然幂等。
        NOVEL_PIC.find(url)?.let { match ->
            return NOVEL_PIC_BASE + match.groupValues[1] + SHRINK_SUFFIX
        }
        if (url.contains(".heic")) return url.replace(".heic", ".image")
        return if (url.startsWith("//")) "https:$url" else url
    }
}
