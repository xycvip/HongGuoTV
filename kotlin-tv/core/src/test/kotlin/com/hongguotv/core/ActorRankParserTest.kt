// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActorRankParserTest {
    @Test
    fun parsesActorRowsAndPaginationCursor() {
        val raw = """
            {
              "code": 0,
              "data": {
                "has_more": true,
                "next_offset": 2,
                "session_id": "actor-session",
                "rank_version": "1792811520",
                "cell_view": {
                  "cell_data": [{
                    "ugc_user_data": [{
                      "user_info": {
                        "user_id": "7100000000001301",
                        "base_info": {
                          "user_name": "测试演员",
                          "user_avatar": "https://p3-reading-sign.fqnovelpic.com/novel-pic/actor-avatar~tplv-81nmtwyey9.heic",
                          "actor_id_str": "7100000000001401"
                        }
                      },
                      "rec_text_item": {"RecommendText": "700万人气"},
                      "intro": "代表作品《测试短剧》",
                      "sub_title_list": [{"content": "10部作品"}],
                      "rank_change_data": {"rank_change_type": 1, "ChangeNum": 2}
                    }]
                  }]
                }
              }
            }
        """.trimIndent()

        val page = ActorRankParser.parse(raw, 0)
        assertEquals(1, page.items.size)
        assertEquals("7100000000001301", page.items.single().userId)
        assertEquals("7100000000001401", page.items.single().actorId)
        assertEquals("https://p3-novel.byteimg.com/novel-pic/actor-avatar~tplv-shrink:640:0.image", page.items.single().avatar)
        assertEquals("测试演员", page.items.single().name)
        assertEquals("700万人气", page.items.single().popularity)
        assertEquals(1, page.items.single().position)
        assertEquals("10部作品", page.items.single().details.single())
        assertEquals(2, page.nextOffset)
        assertEquals("actor-session", page.sessionId)
        assertTrue(page.hasMore)
    }

    @Test
    fun parsesStarBoardCellPayloadAndAlternatePaginationFields() {
        val raw = """
            {
              "code": 0,
              "message": "SUCCESS",
              "data": {
                "has_next": true,
                "rank_session": "star-session",
                "cell": [{
                  "ugc_user_data": [{
                    "user_info": {
                      "user_id": "7200000000001301",
                      "base_info": {
                        "user_name": "星光榜演员",
                        "user_avatar": "https://p3-reading-sign.fqnovelpic.com/novel-pic/star-avatar.heic",
                        "actor_id_str": "7200000000001401"
                      }
                    },
                    "rec_text_item": {"RecommendText": "900万人气"}
                  }]
                }]
              }
            }
        """.trimIndent()

        val page = ActorRankParser.parse(raw, 0)
        assertEquals(1, page.items.size)
        assertEquals("星光榜演员", page.items.single().name)
        assertEquals("7200000000001301", page.items.single().userId)
        assertEquals("7200000000001401", page.items.single().actorId)
        assertEquals("900万人气", page.items.single().popularity)
        assertEquals("star-session", page.sessionId)
        assertTrue(page.hasMore)
    }
}
