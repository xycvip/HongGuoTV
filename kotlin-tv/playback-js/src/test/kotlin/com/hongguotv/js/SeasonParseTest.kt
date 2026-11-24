// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * seasons 导出的解析回归（不连网）。
 *
 * 脚本返回的是数组 `[{ number, drama }]`，曾经被 JSONObject 解析后当成空列表，
 * 详情页因此一直没有「系列剧」。这里锁定解析、排序与季标签。
 */
class SeasonParseTest {
    private val sample = """
        [
          {"number":2,"drama":{"sourceId":"7000000000002","title":"星河故事 第二季","cover":"https://x/2.jpg","tags":["都市","逆袭"]}},
          {"number":1,"drama":{"sourceId":"7000000000001","title":"星河故事 第一季","cover":"https://x/1.jpg","tags":["都市"]}},
          {"number":3,"drama":{"sourceId":"bad-id","title":"无效条目"}},
          {"number":0,"drama":{"sourceId":"7000000000003","title":"星河故事 第三季"}}
        ]
    """.trimIndent()

    @Test
    fun `数组结果按季序号升序并保留标题与封面`() {
        val seasons = HongguoJsContent.parseSeasons(sample)
        assertEquals(3, seasons.size)
        assertEquals(listOf("7000000000001", "7000000000002", "7000000000003"), seasons.map { it.series.id })
        assertEquals("第一季", seasons[0].label)
        assertEquals("第二季", seasons[1].label)
        assertEquals("https://x/2.jpg", seasons[1].series.cover)
        assertEquals("都市 · 逆袭", seasons[1].series.tags)
    }

    @Test
    fun `缺 number 时回退到标题里的季序号`() {
        val raw = """[{"number":0,"drama":{"sourceId":"7000000000009","title":"某剧 第五季"}}]"""
        val seasons = HongguoJsContent.parseSeasons(raw)
        assertEquals(1, seasons.size)
        assertEquals(5, seasons[0].number)
        assertEquals("第五季", seasons[0].label)
    }

    @Test
    fun `对象或空结果不会当成系列剧`() {
        assertTrue(HongguoJsContent.parseSeasons("""{"items":[]}""").isEmpty())
        assertTrue(HongguoJsContent.parseSeasons("null").isEmpty())
        assertTrue(HongguoJsContent.parseSeasons("").isEmpty())
    }
}
