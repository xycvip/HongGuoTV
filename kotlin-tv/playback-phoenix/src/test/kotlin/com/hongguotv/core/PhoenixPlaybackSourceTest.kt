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
            "high":{"main_url":"https://cdn.example/1080.mp4","video_meta":{"codec_type":"h265","vheight":1080,"definition":"1080P"}},
            "normal":{"main_url":["https://cdn.example/720.mp4"],"video_meta":{"codec_type":"h264","vheight":720,"definition":"720P"}}
          }}}
        }"""
        val result = PhoenixPlaybackSource.parseResponse(json, 720)
        assertEquals("https://cdn.example/720.mp4", result.url)
        assertEquals("720P", result.quality)
    }

    @Test fun `supports dynamic video list when regular list is empty`() {
        val json = """{
          "code":0,
          "data":{"video_model":{"video_list":{},
            "dynamic_video":{"dynamic_video_list":[
              {"main_url":"https://cdn.example/dynamic.mp4",
               "video_meta":{"codec_type":"h264","vheight":540,"definition":"540P"}}
            ]}}
          }
        }"""
        val result = PhoenixMediaResolver.parse(json, 1080)
        assertEquals("https://cdn.example/dynamic.mp4", result.url)
        assertEquals("540P", result.quality)
    }

    @Test fun `actual resolver reports each candidate decision without exposing full URLs`() {
        val raw = """{"code":0,"data":{"video_model":{"video_list":{
          "unsupported":{"main_url":"https://cdn.example/private-path/video.mp4?token=secret",
            "video_meta":{"codec_type":"bytevc2","mime_type":"video/hevc","definition":"1080P","vheight":1080}},
          "playable":{"main_url":"https://media.example/path/720.mp4?signature=secret",
            "video_meta":{"codec_type":"h264","mime_type":"video/avc","definition":"720P","vheight":720}}
        }}}}"""
        val diagnostics = mutableListOf<String>()
        val result = PhoenixMediaResolver.parse(raw, 1080, onDiagnostic = diagnostics::add)

        assertEquals("https://media.example/path/720.mp4?signature=secret", result.url)
        assertEquals("720P", result.quality)
        assertTrue(diagnostics.any { it.contains("unsupported") && it.contains("不兼容编码") })
        assertTrue(diagnostics.any { it.contains("playable") && it.contains("通过") })
        assertTrue(diagnostics.any { it.contains("筛选汇总") && it.contains("候选项=2") })
        assertTrue(diagnostics.none { it.contains("signature=secret") || it.contains("token=secret") })
    }

    @Test fun `rejects protected media instead of attempting key extraction`() {
        val json = """{"code":0,"data":{"video_model":{"video_list":[
          {"main_url":"https://cdn.example/protected.mp4",
           "encrypt_info":{"encrypt":true,"encryption_method":"cenc-aes-ctr"}}
        ]}}}"""
        assertThrows(UnplayableVideoException::class.java) {
            PhoenixPlaybackSource.parseResponse(json)
        }
    }


    @Test fun `extracts fallback endpoint from object and fetch response supplies final stream`() {
        val initial = """{"code":0,"data":{"video_model":"{\"status\":10,\"fallback_api\":{\"fallback_api\":\"https://api.example/fallback\"},\"video_list\":{}}"} }"""
        assertEquals("https://api.example/fallback", PhoenixMediaResolver.fallbackUrl(initial))
        val fallback = """{"video_info":{"data":{"video_list":{"video_1":{"main_url":"https://cdn.example/final.mp4","codec_type":"h264","quality_desc":"720P","height":720}}}}}"""
        val stream = PhoenixMediaResolver.parse(initial, 720, fallback)
        assertEquals("https://cdn.example/final.mp4", stream.url)
        assertEquals("720P", stream.quality)
    }

    @Test fun `extracts deeply nested fallback endpoint`() {
        val initial = """{"code":0,"data":{"video_model":{
          "fallback_api":{"data":{"fallback_api":{"url":"https://api.example/deep-fallback"}}},
          "video_list":{}
        }}}"""
        assertEquals("https://api.example/deep-fallback", PhoenixMediaResolver.fallbackUrl(initial))
    }

    @Test fun `fallback parser rejects bytevc2 but keeps supported h264 entry`() {
        val initial = """{"code":0,"data":{"video_model":{"fallback_api":"https://api.example/fallback","video_list":{}}}}"""
        val fallback = """{"video_info":{"data":{"video_list":{
          "bad":{"main_url":"https://cdn.example/bvc.mp4","codec_type":"bytevc2","height":1080},
          "good":{"main_url":"https://cdn.example/avc.mp4","codec_type":"h264","height":720}
        }}}}"""
        val stream = PhoenixMediaResolver.parse(initial, 1080, fallback)
        assertEquals("https://cdn.example/avc.mp4", stream.url)
    }

    @Test fun `fallback parser does not pass encrypted item without usable spade key`() {
        val initial = """{"code":0,"data":{"video_model":{"video_list":{}}}}"""
        val fallback = """{"video_info":{"data":{"video_list":{
          "bad":{"main_url":"https://cdn.example/encrypted.mp4","codec_type":"h264","height":720,
            "encrypt_info":{"encrypt":true,"encryption_method":"cenc-aes-ctr"}}
        }}}}"""
        assertThrows(UnplayableVideoException::class.java) {
            PhoenixMediaResolver.parse(initial, 1080, fallback)
        }
    }


    @Test fun `parses base64 video model and uses vwidth when vheight is absent`() {
        val model = """{"video_list":[
          {"main_url":"https://cdn.example/portrait.mp4",
           "video_meta":{"codec_type":"h264","vwidth":720}}
        ]}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(model.toByteArray(Charsets.UTF_8))
        val raw = """{"code":0,"data":{"video_model":"$encoded"}}"""
        val stream = PhoenixMediaResolver.parse(raw, 1080)
        assertEquals("https://cdn.example/portrait.mp4", stream.url)
        assertEquals("720P", stream.quality)
    }

    @Test fun `falls back to outer data video list when video model list is empty`() {
        val raw = """{"code":0,"data":{
          "video_model":{"video_list":{}},
          "video_list":[
            {"main_url":"https://cdn.example/outer.mp4",
             "video_meta":{"codec_type":"h264","definition":"540P","vheight":540}}
          ]
        }}"""
        val stream = PhoenixMediaResolver.parse(raw, 1080)
        assertEquals("https://cdn.example/outer.mp4", stream.url)
        assertEquals("540P", stream.quality)
    }

    @Test fun `rejects empty or unsuccessful response`() {
        assertThrows(java.io.IOException::class.java) {
            PhoenixPlaybackSource.parseResponse("""{"code":0,"data":{}}""")
        }
        assertThrows(java.io.IOException::class.java) {
            PhoenixPlaybackSource.parseResponse("""{"code":1001,"data":{}}""")
        }
    }
}
