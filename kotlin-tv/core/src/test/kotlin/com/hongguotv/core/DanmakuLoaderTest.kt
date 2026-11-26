package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * DanmakuLoader 的缓冲/去重/退避逻辑。窗口数据源用假实现注入
 * （真实数据来自热更新 JS 门面 HongguoJsDanmaku）。
 */
class DanmakuLoaderTest {
    private var now = 0L

    private fun loader(
        durationMs: Long,
        leadMs: Long = 60_000,
        respond: (Long) -> DanmakuWindow,
    ): Pair<DanmakuLoader, MutableList<Long>> {
        now = 0L
        val requested = mutableListOf<Long>()
        val source = object : DanmakuWindowSource {
            override fun window(seriesId: String, vid: String, durationMs: Long, startMs: Long): DanmakuWindow {
                requested += startMs
                return respond(startMs)
            }
        }
        return DanmakuLoader(source, "123", "999", durationMs, leadMs, { now }) to requested
    }

    private fun window(start: Long, next: Long, texts: List<String>) = DanmakuWindow(
        comments = texts.mapIndexed { index, text ->
            Danmaku(id = "$start-$index", offsetMs = start + index * 1000, text = text)
        },
        nextStartMs = next,
    )

    @Test fun buffersWindowsAheadOfPlayback() {
        val (loader, requested) = loader(180_000) { start -> window(start, start + 30_000, listOf("弹幕$start")) }
        loader.loadUpTo(0)
        assertEquals(listOf(0L, 30_000L, 60_000L), requested)
        assertEquals(3, loader.buffered)
        assertEquals(listOf("弹幕0"), loader.between(0, 1_000).map { it.text })
        assertEquals(listOf("弹幕30000"), loader.between(1_000, 31_000).map { it.text })
    }

    @Test fun forwardSeekSkipsUnwatchedWindows() {
        val (loader, requested) = loader(600_000) { start -> window(start, start + 30_000, emptyList()) }
        loader.loadUpTo(0)
        requested.clear()
        loader.loadUpTo(300_000)
        assertEquals(listOf(300_000L, 330_000L, 360_000L), requested)
    }

    @Test fun stopsRequestingOnceEpisodeFullyBuffered() {
        val (loader, requested) = loader(60_000) { start -> window(start, start + 30_000, emptyList()) }
        loader.loadUpTo(0)
        val count = requested.size
        loader.loadUpTo(50_000)
        assertEquals(count, requested.size)
    }

    @Test fun failuresBackOffAndEventuallyGiveUpSilently() {
        val (loader, requested) = loader(600_000) { throw IOException("offline") }
        loader.loadUpTo(0)
        assertEquals(1, requested.size)
        loader.loadUpTo(0)
        assertEquals(1, requested.size)
        // 退避递增：8s → 16s → 32s → 60s 封顶，给 10 次机会后才彻底安静。
        val backoffs = listOf(8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L)
        var expected = 1
        for (backoff in backoffs) {
            now += backoff - 1
            loader.loadUpTo(0)
            assertEquals("退避未到不该重试", expected, requested.size)
            now += 1
            loader.loadUpTo(0)
            expected++
            assertEquals("退避到期应重试", expected, requested.size)
        }
        assertEquals(10, requested.size)
        // 连续失败 10 次后彻底安静，不再无休止地打接口。
        now += 120_000
        loader.loadUpTo(0)
        assertEquals(10, requested.size)
    }

    @Test fun betweenFiltersByOffsetRange() {
        val (loader, _) = loader(120_000) { start -> window(start, start + 30_000, listOf("a", "b", "c")) }
        loader.loadUpTo(0)
        assertEquals(listOf("a", "b"), loader.between(0, 2_000).map { it.text })
        assertTrue(loader.between(5_000, 5_000).isEmpty())
    }


    @Test fun cancelledLoaderDoesNotFetchAgain() {
        val (loader, requested) = loader(180_000, leadMs = 0) { start -> window(start, start + 30_000, emptyList()) }
        loader.loadUpTo(0)
        val count = requested.size
        loader.cancel()
        loader.loadUpTo(90_000)
        assertEquals(count, requested.size)
    }

    @Test fun backwardSeekReopensEarlierWindows() {
        val (loader, requested) = loader(900_000, leadMs = 0) { start -> window(start, start + 30_000, listOf("弹幕$start")) }
        loader.loadUpTo(300_000)
        assertEquals(listOf(300_000L), requested)
        loader.loadUpTo(0)
        assertEquals(listOf(300_000L, 0L), requested)
        assertEquals(listOf("弹幕0"), loader.between(0, 1_000).map { it.text })
    }

    @Test fun bufferedCommentsHaveHardLimit() {
        val (loader, _) = loader(180_000, leadMs = 0) { start ->
            DanmakuWindow(
                comments = (0 until 5_000).map { index -> Danmaku(id = "$start-$index", offsetMs = start + index, text = "弹幕$index") },
                nextStartMs = start + 30_000,
            )
        }
        loader.loadUpTo(0)
        assertEquals(4_000, loader.buffered)
    }

    @Test fun unknownDurationWaitsForMetadata() {
        val (loader, requested) = loader(0) { start -> window(start, start + 30_000, emptyList()) }
        loader.loadUpTo(0)
        assertTrue(requested.isEmpty())
        loader.durationMs = 120_000
        loader.loadUpTo(0)
        assertFalse(requested.isEmpty())
    }

    @Test fun duplicateIdsAreDedupedAcrossWindows() {
        // 两个窗口返回相同的弹幕 id，跨窗口去重后应只剩 2 条。
        val (loader, _) = loader(60_000) { start ->
            DanmakuWindow(
                comments = listOf(
                    Danmaku(id = "shared-a", offsetMs = start, text = "同一句话"),
                    Danmaku(id = "shared-b", offsetMs = start + 1_000, text = "另一句"),
                ),
                nextStartMs = start + 30_000,
            )
        }
        loader.loadUpTo(0)
        assertEquals(2, loader.buffered)
    }
}
