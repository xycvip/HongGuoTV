package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoenixPlaybackSourceTest {
    @Test fun `parses video model and chooses h264 variant within requested quality`() {
        val json = """{
          "code":0,
          "data":{"video_model":{"video_list":{
            "high":{"main_url":"https://cdn.qznovelvod.com/1080.mp4","video_meta":{"codec_type":"h265","vheight":1080,"definition":"1080P"}},
            "normal":{"main_url":"https://cdn.qznovelvod.com/720.mp4","video_meta":{"codec_type":"h264","vheight":720,"definition":"720P"}}
          }}}
        }"""
        val result = PhoenixMediaResolver.parse(json, 720)
        assertEquals("https://cdn.qznovelvod.com/720.mp4", result.url)
        assertEquals("720P", result.quality)
    }

    @Test fun `does not accept dynamic video list outside OOIUa video_list contract`() {
        val json = """{
          "code":0,
          "data":{"video_model":{"video_list":{},
            "dynamic_video":{"dynamic_video_list":[
              {"main_url":"https://cdn.qznovelvod.com/dynamic.mp4",
               "video_meta":{"codec_type":"h264","vheight":540,"definition":"540P"}}
            ]}}
          }
        }"""
        assertThrows(java.io.IOException::class.java) {
            PhoenixMediaResolver.parse(json, 1080)
        }
    }

    @Test fun `actual resolver reports each candidate decision without exposing full URLs`() {
        val raw = """{"code":0,"data":{"video_model":{"video_list":{
          "unsupported":{"main_url":"https://cdn.qznovelvod.com/private-path/video.mp4?token=secret",
            "video_meta":{"codec_type":"bytevc2","mime_type":"video/hevc","definition":"1080P","vheight":1080}},
          "playable":{"main_url":"https://media.fqnovelvod.com/path/720.mp4?signature=secret",
            "video_meta":{"codec_type":"h264","mime_type":"video/avc","definition":"720P","vheight":720}}
        }}}}"""
        val diagnostics = mutableListOf<String>()
        val result = PhoenixMediaResolver.parse(raw, 720, onDiagnostic = diagnostics::add)

        assertEquals("https://media.fqnovelvod.com/path/720.mp4?signature=secret", result.url)
        assertEquals("720P", result.quality)
        assertTrue(diagnostics.any { it.contains("unsupported") && it.contains("不兼容编码") })
        assertTrue(diagnostics.any { it.contains("playable") && it.contains("通过") })
        assertTrue(diagnostics.any { it.contains("筛选汇总") && it.contains("候选项=2") })
        assertTrue(diagnostics.none { it.contains("signature=secret") || it.contains("token=secret") })
    }

    @Test fun `rejects protected media instead of attempting key extraction`() {
        val json = """{"code":0,"data":{"video_model":{"video_list":[
          {"main_url":"https://cdn.qznovelvod.com/protected.mp4",
           "encrypt_info":{"encrypt":true,"encryption_method":"cenc-aes-ctr"}}
        ]}}}"""
        assertThrows(UnplayableVideoException::class.java) {
            PhoenixMediaResolver.parse(json, 1080)
        }
    }


    @Test fun `extracts fallback endpoint from object and fetch response supplies final stream`() {
        val initial = """{"code":0,"data":{"video_model":"{\"status\":10,\"fallback_api\":{\"fallback_api\":\"https://api.fqnovel.com/fallback\"},\"video_list\":{}}"} }"""
        assertEquals("https://api.fqnovel.com/fallback", PhoenixMediaResolver.fallbackUrl(initial))
        val fallback = """{"video_info":{"data":{"video_list":{"video_1":{"main_url":"https://cdn.qznovelvod.com/final.mp4","codec_type":"h264","quality_desc":"720P","height":720}}}}}"""
        val stream = PhoenixMediaResolver.parse(initial, 720, fallback)
        assertEquals("https://cdn.qznovelvod.com/final.mp4", stream.url)
        assertEquals("720P", stream.quality)
    }

    @Test fun `does not extract fallback_api from unsupported nested object shapes`() {
        val initial = """{"code":0,"data":{"video_model":{
          "fallback_api":{"data":{"fallback_api":{"url":"https://api.fqnovel.com/deep-fallback"}}},
          "video_list":{}
        }}}"""
        assertEquals("", PhoenixMediaResolver.fallbackUrl(initial))
    }

    @Test fun `fallback parser rejects bytevc2 but keeps supported h264 entry`() {
        val initial = """{"code":0,"data":{"video_model":{"fallback_api":"https://api.fqnovel.com/fallback","video_list":{}}}}"""
        val fallback = """{"video_info":{"data":{"video_list":{
          "bad":{"main_url":"https://cdn.qznovelvod.com/bvc.mp4","codec_type":"bytevc2","height":1080},
          "good":{"main_url":"https://cdn.qznovelvod.com/avc.mp4","codec_type":"h264","height":720}
        }}}}"""
        val stream = PhoenixMediaResolver.parse(initial, 720, fallback)
        assertEquals("https://cdn.qznovelvod.com/avc.mp4", stream.url)
    }

    @Test fun `fallback parser does not pass encrypted item without usable spade key`() {
        val initial = """{"code":0,"data":{"video_model":{"video_list":{}}}}"""
        val fallback = """{"video_info":{"data":{"video_list":{
          "bad":{"main_url":"https://cdn.qznovelvod.com/encrypted.mp4","codec_type":"h264","height":720,
            "encrypt_info":{"encrypt":true,"encryption_method":"cenc-aes-ctr"}}
        }}}}"""
        assertThrows(UnplayableVideoException::class.java) {
            PhoenixMediaResolver.parse(initial, 1080, fallback)
        }
    }


    @Test fun `parses base64 video model with standard video_meta dimensions`() {
        val model = """{"video_list":[
          {"main_url":"https://cdn.qznovelvod.com/portrait.mp4",
           "video_meta":{"codec_type":"h264","vwidth":1280,"vheight":720}}
        ]}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(model.toByteArray(Charsets.UTF_8))
        val raw = """{"code":0,"data":{"video_model":"$encoded"}}"""
        val stream = PhoenixMediaResolver.parse(raw, 720)
        assertEquals("https://cdn.qznovelvod.com/portrait.mp4", stream.url)
        assertEquals("720P", stream.quality)
    }

    @Test fun `does not accept outer data video_list outside OOIUa model contract`() {
        val raw = """{"code":0,"data":{
          "video_model":{"video_list":{}},
          "video_list":[
            {"main_url":"https://cdn.qznovelvod.com/outer.mp4",
             "video_meta":{"codec_type":"h264","definition":"540P","vheight":540}}
          ]
        }}"""
        assertThrows(java.io.IOException::class.java) {
            PhoenixMediaResolver.parse(raw, 1080)
        }
    }

    @Test fun `rejects media URLs outside OOIUa VOD_DOMAINS`() {
        val raw = """{"code":0,"data":{"video_model":{"video_list":[
          {"main_url":"https://cdn.example/outside.mp4",
           "video_meta":{"codec_type":"h264","definition":"720P","vheight":720}}
        ]}}}"""
        assertThrows(UnplayableVideoException::class.java) {
            PhoenixMediaResolver.parse(raw, 720)
        }
    }

    @Test fun `rejects empty or unsuccessful response`() {
        assertThrows(java.io.IOException::class.java) {
            PhoenixMediaResolver.parse("""{"code":0,"data":{}}""", 1080)
        }
        assertThrows(java.io.IOException::class.java) {
            PhoenixMediaResolver.parse("""{"code":1001,"data":{}}""", 1080)
        }
        assertThrows(java.io.IOException::class.java) {
            PhoenixMediaResolver.parse("""{"data":{"video_model":{"video_list":[]}}}""", 1080)
        }
    }
}
