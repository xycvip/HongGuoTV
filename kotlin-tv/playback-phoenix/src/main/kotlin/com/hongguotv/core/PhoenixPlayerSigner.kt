// SPDX-License-Identifier: GPL-3.0-only
// Port of PhoenixReadApi PhoenixReadRequestSigner.SignPlayer (main branch).
package com.hongguotv.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.ArrayDeque
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phoenix App player-request signer. Deliberately separate from the legacy
 * Signer.signPlayback path: Phoenix video_model/v1 uses SignPlayer and does
 * not use the Helios/Medusa headers emitted by the older implementation.
 */
object PhoenixPlayerSigner {
    private val random = SecureRandom()
    private val requestKey = hex("44b9b9d9a4aef9fca493aa757ca3c2c4a496938f")
    private val argusKey = hex("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c")
    private val derivedArgusKey = sm3(argusKey + hex("3ccc567b") + argusKey)
    private val zero16Sm3 = sm3(ByteArray(16))
    private val staticCounters = ByteArrayOutputStreamCompat().apply {
        varint(1, 1); varint(2, 1); varint(3, 1); varint(7, 3348294860L)
    }.toByteArray()
    private val staticEnvironment = ByteArrayOutputStreamCompat().apply {
        bytes(1, "25053RT47C".toByteArray())
        bytes(2, "16".toByteArray())
        bytes(3, "update_64".toByteArray())
        varint(4, 2147483648L)
    }.toByteArray()

    fun headers(
        query: String,
        body: ByteArray,
        deviceId: String,
        nowMs: Long,
        userAgent: String
    ): Map<String, String> {
        val timestamp = (nowMs / 1000L).toInt()
        val queryHash = digest("MD5", query.toByteArray(Charsets.UTF_8))
        val bodyHash = digest("MD5", body)
        val payload = ByteArray(20)
        queryHash.copyOfRange(0, 4).copyInto(payload, 0)
        bodyHash.copyOfRange(0, 4).copyInto(payload, 4)
        payload[12] = 0
        payload[13] = 6
        payload[14] = 11
        payload[15] = 28
        be32(timestamp).copyInto(payload, 16)
        for (i in payload.indices) payload[i] = (payload[i].u() xor requestKey[i].u()).toByte()
        for (i in payload.indices) {
            val mixed = rotateLeft(payload[i], 4).u() xor payload[(i + 1) % payload.size].u()
            payload[i] = (reverseBits(mixed.toByte()).u() xor 0xff xor payload.size).toByte()
        }
        val gorgon = (hex("8404401c0000") + payload).hex()
        val nonce = ByteArray(11).also(random::nextBytes)
        val aid = queryValue(query, "aid").ifBlank { "8662" }
        return linkedMapOf(
            "User-Agent" to userAgent,
            "Accept" to "application/json",
            "X-XS-From-Web" to "0",
            "Sdk-Version" to "2",
            "X-Khronos" to timestamp.toString(),
            "X-Gorgon" to gorgon,
            "X-SS-Req-Ticket" to nowMs.toString(),
            "X-SS-STUB" to bodyHash.hex().uppercase(),
            "X-Argus" to playerArgus(query, body, timestamp, deviceId, nonce),
            "X-Ladon" to playerLadon(aid, timestamp, nonce.copyOfRange(2, 6))
        )
    }

    private fun playerArgus(query: String, body: ByteArray, timestamp: Int, deviceId: String, nonce: ByteArray): String {
        val bodyHash = sm3(digest("MD5", body))
        val queryHash = sm3(query.toByteArray(Charsets.UTF_8))
        val message = ByteArrayOutputStreamCompat().apply {
            varint(1, 0x20200929L * 2L)
            varint(2, 2)
            varint(3, (u32le(nonce, 6) and 0x7fffffffL))
            bytes(4, "8662".toByteArray())
            bytes(5, deviceId.toByteArray(Charsets.UTF_8))
            bytes(6, "1611921764".toByteArray())
            bytes(7, "7.3.5.32".toByteArray())
            bytes(8, "v04.07.01-ml-android".toByteArray())
            varint(9, 135135744)
            bytes(10, ByteArray(8))
            varint(11, 0)
            varint(12, timestamp.toLong() * 2L)
            bytes(13, bodyHash.copyOfRange(0, 6))
            bytes(14, queryHash.copyOfRange(0, 6))
            bytes(15, staticCounters)
            bytes(16, ByteArray(0))
            bytes(20, "none".toByteArray())
            varint(21, 738)
            bytes(23, staticEnvironment)
            varint(25, 2)
        }.toByteArray()
        val data = pad(message)
        val rounds = LongArray(72)
        for (i in 0 until 4) rounds[i] = readLongLE(derivedArgusKey, i * 8)
        val constant = 0x3dc94c3a046d678bUL.toLong()
        for (k in 4 until 72) {
            var mixed = java.lang.Long.rotateRight(rounds[k - 1], 3) xor rounds[k - 3]
            mixed = mixed xor java.lang.Long.rotateRight(mixed, 1)
            rounds[k] = rounds[k - 4].inv() xor mixed xor ((constant ushr ((k - 4) % 62)) and 1L) xor 3L
        }
        var offset = 0
        while (offset < data.size) {
            var left = readLongLE(data, offset)
            var right = readLongLE(data, offset + 8)
            for (round in rounds) {
                val nextRight = left xor
                    (java.lang.Long.rotateLeft(right, 1) and java.lang.Long.rotateLeft(right, 8)) xor
                    java.lang.Long.rotateLeft(right, 2) xor round
                left = right
                right = nextRight
            }
            writeLongLE(data, offset, left)
            writeLongLE(data, offset + 8, right)
            offset += 16
        }
        val xorKey = hex("d04fffd0d04fffd0")
        for (i in data.indices) data[i] = (data[i].u() xor xorKey[i % xorKey.size].u()).toByte()
        val combined = xorKey + data
        combined.reverse()
        val container = byteArrayOf(0xa6.toByte(),0xe7.toByte(),0x83.toByte(),0xee.toByte(),0x70,0x01,0x10,0x09,0x18) +
            combined + byteArrayOf(0x56,0x7b)
        val encryptedInput = pad(container)
        val aesKey = digest("MD5", argusKey.copyOfRange(0, 16))
        val iv = digest("MD5", argusKey.copyOfRange(16, 32))
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return (byteArrayOf(0x3c, 0xcc.toByte()) + cipher.doFinal(encryptedInput)).base64()
    }

    private fun playerLadon(aid: String, timestamp: Int, prefix: ByteArray): String {
        val md5Ascii = digest("MD5", prefix + aid.toByteArray(Charsets.UTF_8)).hex().toByteArray(Charsets.US_ASCII)
        var num = readLongLE(md5Ascii, 0)
        var value = readLongLE(md5Ascii, 8)
        val queue = ArrayDeque<Long>().apply {
            add(readLongLE(md5Ascii, 16)); add(readLongLE(md5Ascii, 24))
        }
        val rounds = LongArray(34)
        for (i in rounds.indices) {
            rounds[i] = num
            val next = (java.lang.Long.rotateRight(value, 8) + num) xor i.toLong()
            queue.add(next)
            num = next xor java.lang.Long.rotateLeft(num, 3)
            value = queue.removeFirst()
        }
        val encrypted = pad("${timestamp}-1611921764-${aid}".toByteArray(Charsets.UTF_8))
        var offset = 0
        while (offset < encrypted.size) {
            num = readLongLE(encrypted, offset)
            value = readLongLE(encrypted, offset + 8)
            for (round in rounds) {
                value = round xor (num + java.lang.Long.rotateRight(value, 8))
                num = value xor java.lang.Long.rotateLeft(num, 3)
            }
            writeLongLE(encrypted, offset, num)
            writeLongLE(encrypted, offset + 8, value)
            offset += 16
        }
        return (prefix + encrypted).base64()
    }

    private fun queryValue(query: String, key: String): String {
        for (part in query.split('&')) {
            val at = part.indexOf('=')
            val name = if (at >= 0) part.substring(0, at) else part
            if (java.net.URLDecoder.decode(name, "UTF-8") == key) {
                return if (at >= 0) java.net.URLDecoder.decode(part.substring(at + 1), "UTF-8") else ""
            }
        }
        return ""
    }

    private fun pad(input: ByteArray): ByteArray {
        val count = 16 - input.size % 16
        return input + ByteArray(count) { count.toByte() }
    }
    private fun rotateLeft(value: Byte, count: Int) = ((value.u() shl count) or (value.u() ushr (8 - count))).toByte()
    private fun reverseBits(value: Byte): Byte {
        var x = value.u()
        x = ((x and 0x55) shl 1) or ((x and 0xaa) ushr 1)
        x = ((x and 0x33) shl 2) or ((x and 0xcc) ushr 2)
        return (((x shl 4) or (x ushr 4)) and 255).toByte()
    }
    private fun u32le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].u().toLong()) or (bytes[offset+1].u().toLong() shl 8) or
        (bytes[offset+2].u().toLong() shl 16) or (bytes[offset+3].u().toLong() shl 24)
    private fun readLongLE(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
    private fun writeLongLE(bytes: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
    }

    private class ByteArrayOutputStreamCompat {
        private val out = java.io.ByteArrayOutputStream()
        fun varint(field: Int, value: Long) {
            writeVarint(out, field.toLong() * 8L)
            writeVarint(out, value)
        }
        fun bytes(field: Int, value: ByteArray) {
            writeVarint(out, field.toLong() * 8L + 2L)
            writeVarint(out, value.size.toLong())
            out.write(value)
        }
        fun toByteArray() = out.toByteArray()
    }
    private fun writeVarint(out: java.io.ByteArrayOutputStream, value: Long) {
        var v = value
        while (java.lang.Long.compareUnsigned(v, 128L) >= 0) {
            out.write(((v and 0x7fL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}
