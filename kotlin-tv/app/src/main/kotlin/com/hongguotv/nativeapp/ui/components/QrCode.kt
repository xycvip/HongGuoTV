// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import java.io.ByteArrayOutputStream
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码生成。模块色用墨色而不是纯黑：印在暖白票根上更柔和，
 * 远看仍然清晰，近看不会像一块硬邦邦的印刷品。
 */
fun qrImageBitmap(content: String, size: Int = 720): ImageBitmap? {
    if (content.isBlank()) return null
    return runCatching {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                // 留 2 个模块的静区：票根是暖白的，白底再垫一层，扫码器才有足够对比。
                EncodeHintType.MARGIN to 2,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
        val pixels = IntArray(size * size)
        val dark = 0xFF0B0D12.toInt()
        val light = 0xFFFFFFFF.toInt()
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) pixels[row + x] = if (matrix[x, y]) dark else light
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
}

/** 把扫码链接编码成 PNG 字节（账号登录流程用，AccountState.qrBitmap 消费）。 */
fun qrPngBytes(content: String, size: Int = 480): ByteArray? = runCatching {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
    val pixels = IntArray(size * size)
    val dark = 0xFF0B0D12.toInt()
    val light = 0xFFFFFFFF.toInt()
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) pixels[row + x] = if (matrix[x, y]) dark else light
    }
    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    stream.toByteArray()
}.getOrNull()

/** 地址不变时复用同一张位图，避免每次重组都重新编码。 */
@Composable
fun rememberQrImage(content: String, size: Int = 720): ImageBitmap? =
    remember(content, size) { qrImageBitmap(content, size) }
