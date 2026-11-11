// SPDX-License-Identifier: GPL-3.0-only
// Ported from OOIUa/hongguo-android Spade.unwrapV1 (GPL-3.0-only).
package com.hongguotv.core

import java.io.IOException

internal object PhoenixSpade {
    fun deriveKey(spadeBase64: String): ByteArray {
        val raw = try { decode64(spadeBase64) } catch (error: Exception) {
            throw IOException("spade_a 格式异常", error)
        }
        val key = unwrapV1(raw, 0)
        if (!isValidKey(key)) throw IOException("spade_a 无法解出有效内容密钥")
        return hex(key!!)
    }

    internal fun unwrapV1(spade: ByteArray, flag: Int): String? {
        val length = spade.size
        if (length < 3) return null
        val typeLength = ((spade[0].u() xor spade[1].u() xor spade[2].u()) and 0xff) - 0x30
        if (typeLength < 1) return null
        val workLength = length - (typeLength + 0x30) + 0x2f
        if (workLength < 1 || 1 + workLength > length) return null
        val dest = spade.copyOfRange(1, 1 + workLength)
        val type = ByteArray(typeLength)
        val b16 = spade[length - typeLength - 2].u()
        val b14 = spade[length - typeLength - 1].u()
        for (i in 0 until typeLength) type[i] = (b14 xor b16 xor spade[i + length - typeLength].u()).toByte()
        if (matchesType(type, "app_v2", typeLength) || matchesType(type, "web_v2", typeLength)) return null

        var cur14 = 0x55
        var cur16 = 0xfa
        for (i in 0 until workLength) {
            val current = dest[i].u()
            val bitCount = Integer.bitCount(i)
            val b3: Int
            val b7: Int
            if ((i and 1) != 0) {
                b3 = cur16
                b7 = current
                cur16 = cur14
            } else {
                b3 = current
                b7 = cur14
            }
            val adjustment = if (flag != 0) bitCount + 0x15 else -0x15 - bitCount
            dest[i] = ((adjustment + (cur16 xor current)) and 0xff).toByte()
            cur14 = b7
            cur16 = b3
        }
        val first = dest[0].u()
        val nibble = when (first) {
            in 0x30..0x39 -> first - 0x30
            in 0x61..0x7a -> first - 0x57
            else -> return null
        }
        val end = workLength - nibble
        if (end < 2) return null
        return String(dest, 1, end - 1, Charsets.ISO_8859_1)
    }

    private fun matchesType(value: ByteArray, expected: String, count: Int): Boolean {
        for (i in 0 until count) {
            val a = if (i < value.size) value[i].u() else 0
            val b = if (i < expected.length) expected[i].code else 0
            if (a != b) return false
            if (a == 0) return true
        }
        return true
    }

    private fun isValidKey(value: String?): Boolean =
        value != null && value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }
}
