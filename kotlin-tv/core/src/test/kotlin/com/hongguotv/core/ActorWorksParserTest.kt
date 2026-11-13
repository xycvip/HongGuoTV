// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActorWorksParserTest {
    @Test
    fun parsesOfficialCelebrityWorksAndMapsDramaCards() {
        val raw = """{"code":0,"data":{"works":{"7100000000001401":{"total_count":2,"has_more":false,"video_list":[
            {"series_id":"7700000000000001","title":"演员作品甲","cover":"https://example.com/a.jpg","episode_cnt":24,"sub_title":"全24集","video_desc":"作品简介"},
            {"series_id":"7700000000000002","title":"演员作品乙","cover":"https://example.com/b.jpg","episode_cnt":12,"sub_title":"全12集"}
        ]}}}}"""
        val page = ActorWorksParser.parse(raw, "7100000000001401", 0, 20)
        assertEquals(2, page.items.size)
        assertEquals("演员作品甲", page.items[0].title)
        assertEquals("https://example.com/a.jpg", page.items[0].cover)
        assertEquals("全24集", page.items[0].badge)
        assertEquals("作品简介", page.items[0].description)
        assertEquals(2, page.nextOffset)
        assertFalse(page.hasMore)
    }

    @Test
    fun slicesCumulativeVideoListForNextPage() {
        val raw = """{"code":0,"data":{"works":{"7100000000001401":{"total_count":3,"video_list":[
            {"series_id":"7700000000000001","title":"甲"},
            {"series_id":"7700000000000002","title":"乙"},
            {"series_id":"7700000000000003","title":"丙"}
        ]}}}}"""
        val page = ActorWorksParser.parse(raw, "7100000000001401", 2, 2)
        assertEquals(listOf("7700000000000003"), page.items.map { it.id })
        assertEquals(3, page.nextOffset)
        assertFalse(page.hasMore)
    }

    @Test
    fun reportsMoreWhenOfficialTotalExceedsCurrentPage() {
        val raw = """{"code":0,"data":{"works":{"7100000000001401":{"total_count":50,"has_more":true,"video_list":[
            {"series_id":"7700000000000001","title":"甲"}
        ]}}}}"""
        val page = ActorWorksParser.parse(raw, "7100000000001401", 0, 20)
        assertTrue(page.hasMore)
    }
}
