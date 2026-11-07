package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test fun `rejects empty or unsuccessful response`() {
        assertThrows(java.io.IOException::class.java) {
            PhoenixPlaybackSource.parseResponse("""{"code":0,"data":{}}""")
        }
        assertThrows(java.io.IOException::class.java) {
            PhoenixPlaybackSource.parseResponse("""{"code":1001,"data":{}}""")
        }
    }
}
