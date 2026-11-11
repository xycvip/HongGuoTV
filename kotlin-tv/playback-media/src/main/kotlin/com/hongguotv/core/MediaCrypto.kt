// Kotlin adaptation of drpys 22261ad; GPL-3.0-only.
package com.hongguotv.core

import java.io.IOException
import java.math.BigInteger

object MediaCrypto {
    data class Sample(val start: Long, val end: Long, val iv: ByteArray, val encryptedOffset: Long = 0, val isVideo: Boolean = false, val sampleIndex: Int = 0)
    data class Prepared(val header: ByteArray, val samples: List<Sample>)
    fun deriveKey(value: String): ByteArray {
        val raw=decode64(value); require(raw.size>=3) { "内容密钥格式异常" }
        val length=minOf(raw.size-(raw[0].u() xor raw[1].u() xor raw[2].u())+47,raw.size-1)
        require(length>=33)
        val work=raw.copyOfRange(1,1+length); var a=85; var b=246
        for(i in work.indices) { val old=work[i].u(); val previous=if(i and 1 != 0) a else b; if(i and 1 != 0) a=old else b=old; work[i]=(-21-Integer.bitCount(i)+(previous xor old)).toByte() }
        return hex(String(work.copyOfRange(1,33),Charsets.US_ASCII)).also { require(it.size==16) }
    }
    fun decryptUrl(value: String, seed: ByteArray): String {
        val raw=decode64(value); require(raw.size>=20 && raw[0].u()==0xa8 && raw[2].u()==1 && raw[3].u()==0)
        val constants=hex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58")
        val h=digest("SHA-512",digest("SHA-512",seed)+constants)
        val bytes=aes("CBC",h.copyOfRange(0,16),h.copyOfRange(16,32),raw.copyOfRange(4,4+(raw.size-4)/16*16))
        var end=bytes.size; val padding=bytes.last().u(); if(padding in 1..16 && padding<=end) end-=padding
        while(end>0 && bytes[end-1].u()==0) end--
        return String(bytes,0,end,Charsets.UTF_8)
    }
    private data class Box(val start: Int,val end: Int,val body: Int,val type: String)
    private fun box(data: ByteArray, at: Int, limit: Int = data.size): Box? {
        if(at<0 || at+8>limit) return null
        var size=data.u32(at); var header=8
        if(size==1L) { if(at+16>limit) return null; size=data.i64(at+8); header=16 }
        if(size==0L) size=(limit-at).toLong()
        if(size<header || size>limit-at) return null
        return Box(at,at+size.toInt(),at+header,String(data,at+4,4,Charsets.US_ASCII))
    }
    private fun find(data: ByteArray,type: String,start: Int,limit: Int): Box? {
        for(at in start..limit-8) if(data[at+4].toInt()==type[0].code) { val b=box(data,at,limit); if(b?.type==type) return b }
        return null
    }
    fun headerEnd(first: ByteArray,total: Long): Int {
        if(first.size<16 || String(first,4,4,Charsets.US_ASCII)!="ftyp") throw IOException("不支持的视频容器")
        val ftyp=first.u32(0)
        if(ftyp<8 || ftyp+8>first.size || String(first,ftyp.toInt()+4,4,Charsets.US_ASCII)!="moov") throw IOException("视频索引不在文件开头")
        val size=first.u32(ftyp.toInt()); val end=ftyp+size
        if(size<8 || end>total || end>16*1024*1024) throw IOException("视频索引大小异常")
        return end.toInt()
    }
    fun prepare(data: ByteArray,key: ByteArray?, requireOoiUaMp4Validation: Boolean = false): Prepared {
        if(key==null) {
            if(requireOoiUaMp4Validation) inspectPlainMp4(data)
            return Prepared(data.copyOf(),emptyList())
        }
        if(requireOoiUaMp4Validation) return prepareSencCenc(data, key)
        val moov=box(data,data.u32(0).toInt()) ?: throw IOException("视频索引不完整")
        require(moov.type=="moov")
        val samples=mutableListOf<Sample>(); val header=data.copyOf()
        var cursor=moov.body
        while(cursor<moov.end) {
            val track=box(data,cursor,moov.end) ?: throw IOException("视频轨道格式异常"); cursor=track.end
            if(track.type!="trak") continue
            val stbl=find(data,"stbl",track.body,track.end) ?: continue
            val saiz=find(data,"saiz",stbl.body,stbl.end) ?: continue
            val saio=find(data,"saio",stbl.body,stbl.end) ?: throw IOException("视频加密索引缺失")
            val stsz=find(data,"stsz",stbl.body,stbl.end) ?: throw IOException("样本索引缺失")
            val stsc=find(data,"stsc",stbl.body,stbl.end) ?: throw IOException("块索引缺失")
            val chunks=find(data,"stco",stbl.body,stbl.end) ?: find(data,"co64",stbl.body,stbl.end) ?: throw IOException("块偏移缺失")
            fun count(at: Int,limit: Int): Int { if(at+4>limit) throw IOException("索引越界"); return data.u32(at).also { if(it>200000) throw IOException("索引数量过大") }.toInt() }
            val n=count(stsz.body+8,stsz.end); val defaultSize=data.u32(stsz.body+4)
            if(defaultSize==0L && stsz.body+12+n.toLong()*4>stsz.end) throw IOException("样本索引截断")
            val sizes=LongArray(n) { if(defaultSize>0) defaultSize else data.u32(stsz.body+12+it*4) }
            val chunkCount=count(chunks.body+4,chunks.end); val width=if(chunks.type=="co64") 8 else 4
            if(chunks.body+8+chunkCount.toLong()*width>chunks.end) throw IOException("块偏移截断")
            val offsets=LongArray(chunkCount) { if(width==8) data.i64(chunks.body+8+it*width) else data.u32(chunks.body+8+it*width) }
            val entryCount=count(stsc.body+4,stsc.end)
            if(stsc.body+8+entryCount.toLong()*12>stsc.end || entryCount==0) throw IOException("块索引截断")
            val entries=(0 until entryCount).map { data.u32(stsc.body+8+it*12) to data.u32(stsc.body+12+it*12) }
            // ISO BMFF auxiliary-info flags may insert an eight-byte type/parameter pair.
            val sizeBase=saiz.body+4+(if(data.i32(saiz.body) and 1 != 0) 8 else 0)
            if(sizeBase+5>saiz.end) throw IOException("辅助索引截断")
            val auxDefault=data[sizeBase].u(); val auxCount=count(sizeBase+1,saiz.end)
            if(auxCount!=n || (auxDefault==0 && sizeBase+5+n>saiz.end)) throw IOException("辅助索引数量不一致")
            val auxSizes=IntArray(n) { if(auxDefault>0) auxDefault else data[sizeBase+5+it].u() }
            val offsetBase=saio.body+4+(if(data.i32(saio.body) and 1 != 0) 8 else 0)
            val offsetWidth=if(data[saio.body].u()==1) 8 else 4
            if(count(offsetBase,saio.end)!=1 || offsetBase+4+offsetWidth>saio.end) throw IOException("不支持的辅助索引布局")
            var aux=if(offsetWidth==8) data.i64(offsetBase+4) else data.u32(offsetBase+4)
            val auxEnd=aux+auxSizes.sumOf { it.toLong() }
            if(aux<0 || auxEnd>data.size) throw IOException("辅助数据超出索引范围")
            var sampleIndex=0; var entry=0
            for(chunk in offsets.indices) {
                while(entry+1<entries.size && entries[entry+1].first<=chunk+1) entry++
                val count=entries[entry].second
                if(count>n-sampleIndex || entries[entry].first>chunk+1) throw IOException("样本与块数量不一致")
                var position=offsets[chunk]
                repeat(count.toInt()) {
                    val length=sizes[sampleIndex]; val auxSize=auxSizes[sampleIndex]
                    if(position<0 || length<=0 || position>Long.MAX_VALUE-length || auxSize<8) throw IOException("样本索引异常")
                    val iv=data.copyOfRange(aux.toInt(),aux.toInt()+8)+ByteArray(8)
                    if(auxSize==8) samples+=Sample(position,position+length,iv)
                    else {
                        if(auxSize<10) throw IOException("不支持的加密样本格式")
                        val subs=(data[aux.toInt()+8].u() shl 8) or data[aux.toInt()+9].u()
                        if(auxSize!=10+subs*6) throw IOException("加密子样本索引异常")
                        var offset=position; var encrypted=0L
                        for(sub in 0 until subs) {
                            val at=aux.toInt()+10+sub*6; val clear=(data[at].u() shl 8) or data[at+1].u(); val size=data.u32(at+2)
                            offset+=clear
                            if(offset+size>position+length) throw IOException("加密子样本越界")
                            if(size>0) samples+=Sample(offset,offset+size,iv,encrypted)
                            encrypted+=size; offset+=size
                        }
                        if(offset!=position+length) throw IOException("加密子样本长度异常")
                    }
                    position+=length; aux+=auxSize; sampleIndex++
                }
            }
            if(sampleIndex!=n) throw IOException("视频样本不完整")
            // Preserve the original codec from frma (AVC and HEVC), not a hardcoded HEVC tag.
            var at=stbl.body
            while(at+8<=stbl.end) {
                val sinf=find(data,"sinf",at,stbl.end) ?: break
                val frma=find(data,"frma",sinf.body,sinf.end) ?: throw IOException("原始编码格式缺失")
                if(frma.body+4>frma.end) throw IOException("原始编码格式无效")
                val original=data.copyOfRange(frma.body,frma.body+4)
                var entryAt=sinf.start-1
                while(entryAt>=stbl.body) {
                    val type=String(data,entryAt,4,Charsets.US_ASCII)
                    if(type=="encv" || type=="enca") { original.copyInto(header,entryAt); break }; entryAt--
                }
                "free".toByteArray().copyInto(header,sinf.start+4); header.fill(0,sinf.body,sinf.end); at=sinf.end
            }
        }
        if(samples.isEmpty()) throw IOException("视频加密索引不可用")
        val sorted=samples.sortedBy { it.start }
        if(sorted.zipWithNext().any { (a,b) -> a.end>b.start }) throw IOException("视频样本重叠")
        return Prepared(header,sorted)
    }

    /**
     * P-only OOIUa-compatible CENC path. This path intentionally accepts only
     * senc per-sample IV encryption with full-sample AES-CTR (flags == 0).
     * Unsupported subsample/pattern layouts are rejected instead of guessed.
     */
    private fun prepareSencCenc(data: ByteArray, key: ByteArray): Prepared {
        if (key.size != 16) throw IOException("CENC 内容密钥长度无效")
        val ftyp = box(data, 0) ?: throw IOException("MP4 文件头无效")
        if (ftyp.type != "ftyp") throw IOException("MP4 缺少 ftyp")
        val moov = box(data, ftyp.end) ?: throw IOException("MP4 索引不完整")
        if (moov.type != "moov" || moov.end != data.size || moov.end > 16 * 1024 * 1024) {
            throw IOException("CENC MP4 索引布局不受支持")
        }
        validateOoiUaBoxTree(data, moov, 0)
        val header = data.copyOf()
        val samples = mutableListOf<Sample>()
        var cursor = moov.body
        var trackCount = 0
        var encryptedTrackCount = 0
        var totalSamples = 0L
        val trackIds = mutableSetOf<Long>()
        while (cursor < moov.end) {
            val track = box(data, cursor, moov.end) ?: throw IOException("MP4 轨道结构损坏")
            cursor = track.end
            if (track.type != "trak") continue
            if (++trackCount > 8) throw IOException("MP4 轨道数量异常")
            val tkhd = directChild(data, track, "tkhd") ?: throw IOException("MP4 缺少 tkhd")
            if (tkhd.body + 4 > tkhd.end) throw IOException("MP4 tkhd 截断")
            val tkhdVersion = data[tkhd.body].u()
            if (tkhdVersion > 1 || tkhd.end - tkhd.body < (if (tkhdVersion == 1) 96 else 84)) {
                throw IOException("MP4 tkhd 格式异常")
            }
            val trackId = data.u32(tkhd.body + if (tkhdVersion == 1) 20 else 12)
            if (trackId == 0L || !trackIds.add(trackId)) throw IOException("MP4 轨道 ID 无效或重复")
            val mdia = directChild(data, track, "mdia") ?: throw IOException("MP4 缺少 mdia")
            val hdlr = directChild(data, mdia, "hdlr") ?: throw IOException("MP4 缺少 hdlr")
            if (hdlr.body + 12 > hdlr.end) throw IOException("MP4 hdlr 截断")
            val handler = String(data, hdlr.body + 8, 4, Charsets.US_ASCII)
            if (handler != "vide" && handler != "soun") throw IOException("MP4 存在不支持的轨道类型")
            val minf = directChild(data, mdia, "minf") ?: throw IOException("MP4 缺少 minf")
            val stbl = directChild(data, minf, "stbl") ?: throw IOException("MP4 缺少 stbl")
            val stsd = directChild(data, stbl, "stsd") ?: throw IOException("MP4 缺少 stsd")
            if (stsd.body + 8 > stsd.end || data.u32(stsd.body + 4) != 1L) throw IOException("MP4 sample description 不受支持")
            val entry = box(data, stsd.body + 8, stsd.end) ?: throw IOException("MP4 sample entry 损坏")
            if (entry.end != stsd.end) throw IOException("MP4 sample description 数量异常")
            for (groupType in listOf("sgpd", "sbgp")) {
                val group = directChild(data, stbl, groupType) ?: continue
                if (group.body + 8 > group.end) throw IOException("MP4 sample group 截断")
                val grouping = String(data, group.body + 4, 4, Charsets.US_ASCII)
                if (grouping != "roll" && grouping != "prol") throw IOException("不支持 CENC sample group：$grouping")
            }
            val senc = directChild(data, stbl, "senc")
            val saiz = directChild(data, stbl, "saiz")
            val saio = directChild(data, stbl, "saio")
            val protected = entry.type == "encv" || entry.type == "enca"
            if (!protected) {
                if (senc != null || saiz != null || saio != null || directChildRange(data, entry.body + (if (entry.type in setOf("avc1", "avc3", "hvc1", "hev1", "av01", "mp4v", "vp08", "vp09", "dvav", "dva1")) 78 else 28), entry.end, "sinf") != null) throw IOException("明文轨道包含 CENC 辅助索引")
                continue
            }
            if ((handler == "vide") != (entry.type == "encv")) throw IOException("受保护 sample entry 与轨道类型不匹配")
            if (senc == null) throw IOException("CENC 缺少逐样本 IV（senc）")
            val fixed = if (entry.type == "encv") 78 else 28
            val sinf = directChildRange(data, entry.body + fixed, entry.end, "sinf")
                ?: throw IOException("CENC 缺少 sinf")
            val frma = directChild(data, sinf, "frma") ?: throw IOException("CENC 缺少 frma")
            val schm = directChild(data, sinf, "schm") ?: throw IOException("CENC 缺少 schm")
            if (frma.body + 4 > frma.end || schm.body + 8 > schm.end ||
                String(data, schm.body + 4, 4, Charsets.US_ASCII) != "cenc") {
                throw IOException("CENC 加密方案或原始编码声明无效")
            }
            val original = String(data, frma.body, 4, Charsets.US_ASCII)
            val allowed = if (handler == "vide")
                setOf("avc1", "avc3", "hvc1", "hev1", "av01", "mp4v", "vp08", "vp09", "dvav", "dva1")
            else setOf("mp4a", "ac-3", "ec-3", "Opus", "fLaC", "samr", "sawb")
            if (original !in allowed || entry.body + fixed > entry.end) throw IOException("CENC 原始编码格式无效")

            if (senc.body + 8 > senc.end || data.u32(senc.body) != 0L) {
                throw IOException("不支持 CENC subsample/pattern 加密")
            }
            val sencCount = data.u32(senc.body + 4)
            if (sencCount < 1 || sencCount > 250_000) throw IOException("CENC IV 数量异常")
            val ivBytes = senc.end - senc.body - 8
            if (ivBytes != sencCount * 8L && ivBytes != sencCount * 16L) throw IOException("CENC IV 长度与数量不匹配")
            val ivSize = (ivBytes / sencCount).toInt()
            val schi = directChild(data, sinf, "schi")
            val tenc = schi?.let { directChild(data, it, "tenc") }
            if (tenc != null) {
                if (tenc.body + 1 > tenc.end) throw IOException("CENC tenc 截断")
                val tencVersion = data[tenc.body].u()
                if (tencVersion > 1) throw IOException("CENC tenc 版本不支持")
                val ivOffset = if (tencVersion == 1) 9 else 4
                if (tenc.body + ivOffset + 17 > tenc.end) throw IOException("CENC tenc KID 截断")
                val declaredIvSize = data[tenc.body + ivOffset].u()
                if (declaredIvSize != 0 && declaredIvSize != ivSize) throw IOException("CENC IV 长度与 tenc 声明不一致")
            }

            val stsz = directChild(data, stbl, "stsz") ?: throw IOException("MP4 缺少 stsz")
            if (stsz.body + 12 > stsz.end) throw IOException("MP4 stsz 截断")
            val uniformSize = data.u32(stsz.body + 4)
            val sampleCount = data.u32(stsz.body + 8)
            if (sampleCount != sencCount || sampleCount > 250_000 ||
                stsz.end - stsz.body != 12L + if (uniformSize == 0L) sampleCount * 4L else 0L) {
                throw IOException("CENC 样本数量与 stsz 不匹配")
            }
            val sizes = LongArray(sampleCount.toInt()) { index ->
                val size = if (uniformSize > 0) uniformSize else data.u32(stsz.body + 12 + index * 4)
                if (size <= 0 || size > 8L * 1024 * 1024) throw IOException("CENC 样本大小异常")
                size
            }
            val chunks = directChild(data, stbl, "stco") ?: directChild(data, stbl, "co64")
                ?: throw IOException("MP4 缺少 chunk offset")
            val offsetWidth = if (chunks.type == "co64") 8 else 4
            if (chunks.body + 8 > chunks.end) throw IOException("MP4 chunk offset 截断")
            val chunkCount = data.u32(chunks.body + 4)
            if (chunkCount < 1 || chunkCount > sampleCount ||
                chunks.end - chunks.body != 8L + chunkCount * offsetWidth) throw IOException("MP4 chunk offset 数量异常")
            val offsets = LongArray(chunkCount.toInt()) { index ->
                if (offsetWidth == 8) data.i64(chunks.body + 8 + index * 8)
                else data.u32(chunks.body + 8 + index * 4)
            }
            val stsc = directChild(data, stbl, "stsc") ?: throw IOException("MP4 缺少 stsc")
            if (stsc.body + 8 > stsc.end) throw IOException("MP4 stsc 截断")
            val mapCount = data.u32(stsc.body + 4)
            if (mapCount < 1 || mapCount > chunkCount || stsc.end - stsc.body != 8L + mapCount * 12L) {
                throw IOException("MP4 stsc 映射数量异常")
            }
            val maps = (0 until mapCount.toInt()).map { index ->
                val at = stsc.body + 8 + index * 12
                Triple(data.u32(at), data.u32(at + 4), data.u32(at + 8))
            }
            if (maps.first().first != 1L || maps.any { it.second < 1L || it.third != 1L } ||
                maps.zipWithNext().any { it.first.first >= it.second.first } || maps.last().first > chunkCount) {
                throw IOException("MP4 stsc 映射无效")
            }
            var sampleIndex = 0
            var mapIndex = 0
            val trackSamples = mutableListOf<Sample>()
            for (chunkIndex in offsets.indices) {
                val chunkNumber = chunkIndex + 1L
                while (mapIndex + 1 < maps.size && maps[mapIndex + 1].first <= chunkNumber) mapIndex++
                if (maps[mapIndex].first > chunkNumber) throw IOException("MP4 chunk 映射缺失")
                var position = offsets[chunkIndex]
                repeat(maps[mapIndex].second.toInt()) {
                    if (sampleIndex >= sizes.size) throw IOException("MP4 样本映射超出范围")
                    val length = sizes[sampleIndex]
                    if (position < 0 || position > Long.MAX_VALUE - length) throw IOException("MP4 样本偏移溢出")
                    val ivAt = senc.body + 8 + sampleIndex * ivSize
                    val rawIv = data.copyOfRange(ivAt, ivAt + ivSize)
                    val counterIv = if (ivSize == 8) rawIv + ByteArray(8) else rawIv
                    trackSamples += Sample(position, position + length, counterIv, 0, handler == "vide", sampleIndex)
                    position += length
                    sampleIndex++
                }
            }
            if (sampleIndex != sizes.size) throw IOException("MP4 样本映射不完整")
            if (trackSamples.zipWithNext().any { it.first.end > it.second.start }) throw IOException("MP4 轨道样本重叠")
            samples += trackSamples
            totalSamples += sampleIndex
            if (totalSamples > 250_000L) throw IOException("MP4 总样本数量过大")

            original.toByteArray(Charsets.US_ASCII).copyInto(header, entry.start + 4)
            "free".toByteArray(Charsets.US_ASCII).copyInto(header, sinf.start + 4)
            "free".toByteArray(Charsets.US_ASCII).copyInto(header, senc.start + 4)
            if (saiz != null) "free".toByteArray(Charsets.US_ASCII).copyInto(header, saiz.start + 4)
            if (saio != null) "free".toByteArray(Charsets.US_ASCII).copyInto(header, saio.start + 4)
            encryptedTrackCount++
        }
        // OOIUa removes any PSSH declarations while preserving every byte and offset.
        var moovChildAt = moov.body
        while (moovChildAt < moov.end) {
            val child = box(data, moovChildAt, moov.end) ?: throw IOException("MP4 moov 子框损坏")
            if (child.type == "pssh") "free".toByteArray(Charsets.US_ASCII).copyInto(header, child.start + 4)
            moovChildAt = child.end
        }
        val mvhd = directChild(data, moov, "mvhd") ?: throw IOException("MP4 缺少 mvhd")
        if (mvhd.body + 4 > mvhd.end) throw IOException("MP4 mvhd 截断")
        val mvhdVersion = data[mvhd.body].u()
        if (mvhdVersion > 1 || mvhd.end - mvhd.body < (if (mvhdVersion == 1) 24 else 16)) {
            throw IOException("MP4 mvhd 格式异常")
        }
        if (trackCount == 0 || encryptedTrackCount == 0 || samples.none { it.isVideo }) {
            throw IOException("MP4 没有可验证的 CENC 视频轨")
        }
        val sorted = samples.sortedBy { it.start }
        if (sorted.zipWithNext().any { it.first.end > it.second.start }) throw IOException("MP4 加密样本重叠")
        return Prepared(header, sorted)
    }

    /** Validate the same bounded ISO-BMFF box tree accepted by OOIUa Mp4Progressive. */
    private fun validateOoiUaBoxTree(data: ByteArray, parent: Box, depth: Int) {
        if (depth > 16) throw IOException("MP4 box 嵌套过深")
        if (parent.type in setOf("mvex", "moof", "traf", "stz2", "uuid")) {
            throw IOException("不支持的 MP4 结构：\${parent.type}")
        }
        val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "sinf", "schi")
        val childStart = when {
            parent.type == "stsd" -> {
                if (parent.body + 8 > parent.end) throw IOException("MP4 stsd 截断")
                parent.body + 8
            }
            parent.type in setOf("avc1", "avc3", "hvc1", "hev1", "av01", "mp4v", "vp08", "vp09", "dvav", "dva1", "encv") -> {
                if (parent.body + 78 > parent.end) throw IOException("MP4 视频 sample entry 截断")
                parent.body + 78
            }
            parent.type in setOf("mp4a", "ac-3", "ec-3", "Opus", "fLaC", "samr", "sawb", "enca") -> {
                if (parent.body + 28 > parent.end) throw IOException("MP4 音频 sample entry 截断")
                if (data[parent.body + 8].u() != 0 || data[parent.body + 9].u() != 0) {
                    throw IOException("MP4 音频 sample entry 保留字段无效")
                }
                parent.body + 28
            }
            parent.type in containers -> parent.body
            else -> return
        }
        if (parent.type == "sgpd" || parent.type == "sbgp") {
            if (parent.body + 8 > parent.end) throw IOException("MP4 sample group 截断")
            val grouping = String(data, parent.body + 4, 4, Charsets.US_ASCII)
            if (grouping != "roll" && grouping != "prol") throw IOException("不支持 MP4 sample group：\${grouping}")
            return
        }
        var at = childStart
        while (at < parent.end) {
            val child = box(data, at, parent.end) ?: throw IOException("MP4 子框结构损坏")
            validateOoiUaBoxTree(data, child, depth + 1)
            at = child.end
        }
    }

    private fun directChildRange(data: ByteArray, start: Int, end: Int, type: String): Box? {
        var found: Box? = null
        var at = start
        while (at < end) {
            val child = box(data, at, end) ?: throw IOException("MP4 sample entry 子框损坏")
            if (child.type == type) {
                if (found != null) throw IOException("MP4 存在重复的 $type 框")
                found = child
            }
            at = child.end
        }
        return found
    }

    fun verifyVideoIv(prepared: Prepared, key: ByteArray, fetchSample: (Sample) -> ByteArray) {
        val videoSamples = prepared.samples.filter { it.isVideo }
        if (videoSamples.isEmpty()) throw IOException("CENC 视频样本缺失")
        val probeIndices = listOf(0, 1, 2, videoSamples.size / 4, videoSamples.size / 2, videoSamples.lastIndex)
            .distinct().filter { it in videoSamples.indices && videoSamples[it].end - videoSamples[it].start >= 8 }
        if (probeIndices.isEmpty()) throw IOException("CENC 没有可自证的视频样本")
        for (index in probeIndices) {
            val sample = videoSamples[index]
            val encrypted = fetchSample(sample)
            if (encrypted.size.toLong() != sample.end - sample.start) throw IOException("CENC 自证样本读取不完整")
            val plain = aes("CTR", key, sample.iv, encrypted)
            if (!avccSelfOk(plain)) throw IOException("CENC 视频 IV/密钥自证失败")
        }
    }

    private fun avccSelfOk(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        if (bytes[0].u() == 0 && bytes[1].u() == 0 &&
            (bytes[2].u() == 1 || (bytes[2].u() == 0 && bytes[3].u() == 1))) return true
        var offset = 0
        var count = 0
        while (offset + 5 <= bytes.size) {
            val length = bytes.u32(offset)
            if (length <= 0 || length > bytes.size - offset - 4) return false
            if (bytes[offset + 4].u() and 0x80 != 0) return false
            offset += 4 + length.toInt()
            if (++count > 64) return false
        }
        return count > 0 && (offset == bytes.size || bytes.size - offset <= 3)
    }

    /**
     * OOIUa's no-key path does not trust omitted encrypt_info: inspect the MP4 moov
     * and reject CENC/protected sample entries before allowing the bytes as plaintext.
     * This opt-in check is only enabled by the Phoenix source; N keeps the old path.
     */
    private fun inspectPlainMp4(data: ByteArray) {
        val ftyp = box(data, 0) ?: throw IOException("明文 MP4 文件头无效")
        if (ftyp.type != "ftyp") throw IOException("明文 MP4 缺少 ftyp")
        val moov = box(data, ftyp.end) ?: throw IOException("明文 MP4 索引不完整")
        if (moov.type != "moov" || moov.end != data.size || moov.end - moov.start > 16 * 1024 * 1024) {
            throw IOException("明文 MP4 索引布局不受支持")
        }
        val forbidden = setOf("encv", "enca", "sinf", "schi", "senc", "saiz", "saio", "pssh", "tenc", "mvex", "moof", "traf", "stz2", "uuid")
        for (type in forbidden) {
            if (containsFourcc(data, moov.start, moov.end, type)) {
                throw IOException("未声明加密的片源实际包含 MP4 加密标记")
            }
        }
        val mvhd = directChild(data, moov, "mvhd") ?: throw IOException("明文 MP4 缺少 mvhd")
        if (mvhd.body + 4 > mvhd.end) throw IOException("明文 MP4 mvhd 截断")
        val movieVersion = data[mvhd.body].u()
        if (movieVersion > 1 || mvhd.end - mvhd.body < (if (movieVersion == 1) 24 else 16)) {
            throw IOException("明文 MP4 mvhd 格式异常")
        }
        val tracks = mutableListOf<Box>()
        var at = moov.body
        while (at < moov.end) {
            val child = box(data, at, moov.end) ?: throw IOException("明文 MP4 moov 子框损坏")
            if (child.type == "trak") tracks += child
            at = child.end
        }
        if (tracks.isEmpty() || tracks.size > 8) throw IOException("明文 MP4 轨道数量异常")
        val trackIds = mutableSetOf<Long>()
        var totalSamples = 0L
        for (track in tracks) {
            val tkhd = directChild(data, track, "tkhd") ?: throw IOException("明文 MP4 缺少 tkhd")
            if (tkhd.body + 4 > tkhd.end) throw IOException("明文 MP4 tkhd 截断")
            val version = data[tkhd.body].u()
            if (version > 1 || tkhd.end - tkhd.body < (if (version == 1) 96 else 84)) {
                throw IOException("明文 MP4 tkhd 格式异常")
            }
            val trackId = data.u32(tkhd.body + if (version == 1) 20 else 12)
            if (!trackIds.add(trackId)) throw IOException("明文 MP4 轨道 ID 重复")

            val mdia = directChild(data, track, "mdia") ?: throw IOException("明文 MP4 缺少 mdia")
            val hdlr = directChild(data, mdia, "hdlr") ?: throw IOException("明文 MP4 缺少 hdlr")
            if (hdlr.body + 12 > hdlr.end) throw IOException("明文 MP4 hdlr 截断")
            val handler = String(data, hdlr.body + 8, 4, Charsets.US_ASCII)
            if (handler != "vide" && handler != "soun") throw IOException("明文 MP4 存在不支持的轨道类型")
            val minf = directChild(data, mdia, "minf") ?: throw IOException("明文 MP4 缺少 minf")
            val stbl = directChild(data, minf, "stbl") ?: throw IOException("明文 MP4 缺少 stbl")
            for (groupType in listOf("sgpd", "sbgp")) {
                val group = directChild(data, stbl, groupType) ?: continue
                if (group.body + 8 > group.end) throw IOException("明文 MP4 sample group 截断")
                val grouping = String(data, group.body + 4, 4, Charsets.US_ASCII)
                if (grouping != "roll" && grouping != "prol") {
                    throw IOException("明文 MP4 存在不支持的 sample group")
                }
            }
            val stts = directChild(data, stbl, "stts") ?: throw IOException("明文 MP4 缺少 stts")
            if (stts.body + 8 > stts.end) throw IOException("明文 MP4 stts 截断")
            val timeEntries = data.u32(stts.body + 4)
            if (timeEntries < 1L || timeEntries > 250_000L ||
                (stts.end - stts.body).toLong() != 8L + timeEntries * 8) {
                throw IOException("明文 MP4 stts 长度异常")
            }
            var timedSamples = 0L
            for (i in 0 until timeEntries.toInt()) {
                val entryAt = stts.body + 8 + i * 8
                val samplesInEntry = data.u32(entryAt)
                if (samplesInEntry == 0L) throw IOException("明文 MP4 stts 样本数量无效")
                timedSamples += samplesInEntry
                if (data.u32(entryAt + 4) == 0L) throw IOException("明文 MP4 样本时长无效")
            }
            val stsz = directChild(data, stbl, "stsz") ?: throw IOException("明文 MP4 缺少 stsz")
            if (stsz.body + 12 > stsz.end) throw IOException("明文 MP4 stsz 截断")
            val uniformSize = data.u32(stsz.body + 4)
            val sampleCount = data.u32(stsz.body + 8)
            if (sampleCount < 1L || sampleCount > 250_000L || uniformSize > 8L * 1024 * 1024 ||
                timedSamples != sampleCount) {
                throw IOException("明文 MP4 样本数量或大小异常")
            }
            if ((stsz.end - stsz.body).toLong() != 12L + (if (uniformSize == 0L) sampleCount * 4 else 0L)) {
                throw IOException("明文 MP4 样本索引长度异常")
            }
            if (uniformSize == 0L) {
                for (i in 0 until sampleCount.toInt()) {
                    if (data.u32(stsz.body + 12 + i * 4) == 0L || data.u32(stsz.body + 12 + i * 4) > 8L * 1024 * 1024) {
                        throw IOException("明文 MP4 单个样本过大")
                    }
                }
            }
            val stco = directChild(data, stbl, "stco")
            val co64 = directChild(data, stbl, "co64")
            if (stco != null && co64 != null) throw IOException("明文 MP4 块偏移表重复")
            val chunks = stco ?: co64 ?: throw IOException("明文 MP4 缺少块偏移索引")
            val width = if (co64 != null) 8 else 4
            if (chunks.body + 8 > chunks.end) throw IOException("明文 MP4 块偏移表截断")
            val chunkCount = data.u32(chunks.body + 4)
            if (chunkCount < 1L || chunkCount > sampleCount || (chunks.end - chunks.body).toLong() != 8L + chunkCount * width) {
                throw IOException("明文 MP4 块偏移表异常")
            }
            val stsc = directChild(data, stbl, "stsc") ?: throw IOException("明文 MP4 缺少 stsc")
            if (stsc.body + 8 > stsc.end) throw IOException("明文 MP4 stsc 截断")
            val mappingCount = data.u32(stsc.body + 4)
            if (mappingCount < 1L || mappingCount > chunkCount || (stsc.end - stsc.body).toLong() != 8L + mappingCount * 12) {
                throw IOException("明文 MP4 stsc 长度异常")
            }
            var previousFirst = 0L
            val mappings = ArrayList<Pair<Long, Long>>(mappingCount.toInt())
            for (i in 0 until mappingCount.toInt()) {
                val offset = stsc.body + 8 + i * 12
                val firstChunk = data.u32(offset)
                val perChunk = data.u32(offset + 4)
                val description = data.u32(offset + 8)
                if ((i == 0 && firstChunk != 1L) || firstChunk <= previousFirst ||
                    firstChunk > chunkCount || perChunk < 1L || perChunk > sampleCount || description != 1L) {
                    throw IOException("明文 MP4 stsc 映射异常")
                }
                previousFirst = firstChunk
                mappings += (firstChunk to perChunk)
            }
            var mappedSamples = 0L
            var mappingIndex = 0
            for (chunk in 1..chunkCount.toInt()) {
                while (mappingIndex + 1 < mappings.size && mappings[mappingIndex + 1].first <= chunk) {
                    mappingIndex++
                }
                mappedSamples += mappings[mappingIndex].second
            }
            if (mappedSamples != sampleCount) throw IOException("明文 MP4 样本与块映射不一致")
            val stsd = directChild(data, stbl, "stsd") ?: throw IOException("明文 MP4 缺少 stsd")
            if (stsd.body + 8 > stsd.end || data.u32(stsd.body + 4) != 1L) {
                throw IOException("明文 MP4 sample description 不受支持")
            }
            val entry = box(data, stsd.body + 8, stsd.end) ?: throw IOException("明文 MP4 sample entry 损坏")
            if (entry.end != stsd.end || entry.type == "encv" || entry.type == "enca") {
                throw IOException("未声明加密的片源包含受保护 sample entry")
            }
            val visualEntries = setOf("avc1", "avc3", "hvc1", "hev1", "av01", "mp4v", "vp08", "vp09", "dvav", "dva1")
            val audioEntries = setOf("mp4a", "ac-3", "ec-3", "Opus", "fLaC", "samr", "sawb")
            val fixedEntryBytes = when {
                handler == "vide" && entry.type in visualEntries -> 78
                handler == "soun" && entry.type in audioEntries -> 28
                else -> throw IOException("明文 MP4 sample entry 类型与轨道不匹配")
            }
            if (entry.body + fixedEntryBytes > entry.end) throw IOException("明文 MP4 sample entry 截断")
            var nestedAt = entry.body + fixedEntryBytes
            while (nestedAt < entry.end) {
                val nested = box(data, nestedAt, entry.end) ?: throw IOException("明文 MP4 sample entry 子框损坏")
                nestedAt = nested.end
            }
            totalSamples += sampleCount
            if (totalSamples > 250_000L) throw IOException("明文 MP4 总样本数量过大")
        }
    }

    private fun directChild(data: ByteArray, parent: Box, type: String): Box? {
        var found: Box? = null
        var at = parent.body
        while (at < parent.end) {
            val child = box(data, at, parent.end) ?: throw IOException("MP4 子框结构损坏")
            if (child.type == type) {
                if (found != null) throw IOException("MP4 存在重复的 $type 框")
                found = child
            }
            at = child.end
        }
        return found
    }

    private fun containsFourcc(data: ByteArray, start: Int, end: Int, type: String): Boolean {
        val a = type.toByteArray(Charsets.US_ASCII)
        var i = start + 4
        while (i <= end - 4) {
            if (data[i] == a[0] && data[i + 1] == a[1] &&
                data[i + 2] == a[2] && data[i + 3] == a[3]) return true
            i++
        }
        return false
    }

    fun decrypt(data: ByteArray,start: Long,key: ByteArray?,prepared: Prepared): ByteArray {
        require(start>=0)
        val result=data.copyOf(); val end=start+data.size
        if(start<prepared.header.size) prepared.header.copyInto(result,0,start.toInt(),minOf(end,prepared.header.size.toLong()).toInt())
        if(key==null) return result
        val samples=prepared.samples
        var low=0; var high=samples.size
        while(low<high) { val middle=(low+high) ushr 1; if(samples[middle].end<=start) low=middle+1 else high=middle }
        for(i in low until samples.size) {
            val sample=samples[i]; if(sample.start>=end) break
            val begin=maxOf(start,sample.start); val stop=minOf(end,sample.end); if(begin>=stop) continue
            val relative=begin-sample.start+sample.encryptedOffset; val skip=(relative%16).toInt()
            val counter=(BigInteger(1,sample.iv)+BigInteger.valueOf(relative/16)).and(BigInteger.ONE.shiftLeft(128)-BigInteger.ONE).toByteArray()
            val iv=ByteArray(16); counter.copyInto(iv,maxOf(0,16-counter.size),maxOf(0,counter.size-16),counter.size)
            val decrypted=aes("CTR",key,iv,ByteArray(skip)+result.copyOfRange((begin-start).toInt(),(stop-start).toInt()))
            decrypted.copyInto(result,(begin-start).toInt(),skip)
        }
        return result
    }
}
