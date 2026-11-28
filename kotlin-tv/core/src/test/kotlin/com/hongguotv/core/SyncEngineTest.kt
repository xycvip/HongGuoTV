package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class SyncEngineTest {
    private fun series(id: Int) = Series("$id", "剧 $id", "https://example.com/$id.jpg", "简介", "全 80 集", "剧情")
    private fun localProgress(id: Int, position: Long, updated: Long) =
        BackupProgress(series(id), "${id + 1000}", 2, position, 60_000, false, updated)
    private fun cloudProgress(id: Int, position: Long, updated: Long) =
        CloudProgress(series(id), "${id + 1000}", 2, position, 60_000, updated)
    private fun cloudFav(id: Int, updated: Long = 100) = CloudFavorite(series(id), updated)

    private fun input(
        mode: SyncMode,
        cloudFav: List<CloudFavorite> = emptyList(),
        cloudHist: List<CloudProgress> = emptyList(),
        localFav: List<Series> = emptyList(),
        localHist: List<BackupProgress> = emptyList(),
        record: SyncRecord = SyncRecord(),
    ) = SyncEngine.Input(mode, cloudFav, cloudHist, localFav, localHist, record)

    @Test fun `OFF 模式完全隔离，不合并也不上传`() {
        val r = SyncEngine.merge(
            input(
                SyncMode.OFF,
                cloudFav = listOf(cloudFav(1)),
                cloudHist = listOf(cloudProgress(2, 100, 500)),
                localFav = listOf(series(3)),
                localHist = listOf(localProgress(4, 100, 100)),
            )
        )
        assertEquals(listOf("3"), r.mergedFavorites.map { it.id })
        assertEquals(listOf("4"), r.mergedHistory.map { it.series.id })
        assertTrue(r.favoritesToUpload.isEmpty())
        assertTrue(r.progressToUpload.isEmpty())
        assertEquals(0, r.totalChanges)
    }

    @Test fun `MERGE 收藏取并集，本机独有上传、云端独有下载`() {
        val r = SyncEngine.merge(
            input(
                SyncMode.MERGE,
                cloudFav = listOf(cloudFav(1), cloudFav(2)),
                localFav = listOf(series(2), series(3)),
            )
        )
        assertEquals(setOf("1", "2", "3"), r.mergedFavorites.map { it.id }.toSet())
        assertEquals(1, r.uploadedFavorites)
        assertEquals(listOf("3"), r.favoritesToUpload.map { it.id })
        assertEquals(2, r.downloadedFavorites)
    }

    @Test fun `MERGE 历史按更新时间取新`() {
        // id=1 云端更新，id=2 本机更新，id=3 仅云端，id=4 仅本机。
        val r = SyncEngine.merge(
            input(
                SyncMode.MERGE,
                cloudHist = listOf(
                    cloudProgress(1, 500, 900),
                    cloudProgress(2, 100, 100),
                    cloudProgress(3, 300, 300),
                ),
                localHist = listOf(
                    localProgress(1, 100, 200),
                    localProgress(2, 800, 800),
                    localProgress(4, 400, 400),
                ),
            )
        )
        val byId = r.mergedHistory.associateBy { it.series.id }
        assertEquals(500L, byId["1"]!!.position) // 云端新
        assertEquals(800L, byId["2"]!!.position) // 本机新
        assertEquals(300L, byId["3"]!!.position) // 云端独有
        assertEquals(400L, byId["4"]!!.position) // 本机独有
        // 仅本机更新的两条上行：id=2 与 id=4。
        assertEquals(setOf("2", "4"), r.progressToUpload.map { it.series.id }.toSet())
    }

    @Test fun `收藏墓碑阻止云端项复活并要求取消云端收藏`() {
        val record = SyncRecord(
            mode = SyncMode.MERGE,
            tombstones = listOf(SyncTombstone("1", 1000, SyncTombstone.Kind.FAVORITE)),
        )
        val r = SyncEngine.merge(
            input(
                SyncMode.MERGE,
                cloudFav = listOf(cloudFav(1), cloudFav(2)),
                localFav = emptyList(),
                record = record,
            )
        )
        assertEquals(listOf("2"), r.mergedFavorites.map { it.id })
        assertEquals(listOf("1"), r.favoriteIdsToRemove)
        assertFalse(r.mergedFavorites.any { it.id == "1" })
    }

    @Test fun `历史墓碑阻止云端记录复活，不上传也不下载`() {
        val record = SyncRecord(
            mode = SyncMode.MERGE,
            tombstones = listOf(SyncTombstone("1", 1000, SyncTombstone.Kind.HISTORY)),
        )
        val r = SyncEngine.merge(
            input(
                SyncMode.MERGE,
                cloudHist = listOf(cloudProgress(1, 500, 900)),
                localHist = emptyList(),
                record = record,
            )
        )
        assertTrue(r.mergedHistory.isEmpty())
        assertTrue(r.progressToUpload.isEmpty())
    }

    @Test fun `CLOUD_WINS 以账号为准，本机独有项被丢弃`() {
        val r = SyncEngine.merge(
            input(
                SyncMode.CLOUD_WINS,
                cloudFav = listOf(cloudFav(1), cloudFav(2)),
                cloudHist = listOf(cloudProgress(1, 500, 900)),
                localFav = listOf(series(2), series(3)), // 3 本机独有 → 丢弃
                localHist = listOf(localProgress(2, 800, 800)), // 2 本机独有 → 丢弃
            )
        )
        assertEquals(setOf("1", "2"), r.mergedFavorites.map { it.id }.toSet())
        assertEquals(listOf("1"), r.mergedHistory.map { it.series.id })
        assertTrue(r.favoritesToUpload.isEmpty())
        assertTrue(r.progressToUpload.isEmpty())
        assertEquals(2, r.downloadedFavorites)
        assertEquals(1, r.downloadedHistory)
    }

    @Test fun `LOCAL_WINS 以本机为准，云端多余项被删除`() {
        val r = SyncEngine.merge(
            input(
                SyncMode.LOCAL_WINS,
                cloudFav = listOf(cloudFav(1), cloudFav(2)),
                cloudHist = listOf(cloudProgress(1, 500, 900), cloudProgress(3, 300, 300)),
                localFav = listOf(series(2), series(3)), // 3 本机独有 → 上传
                localHist = listOf(localProgress(2, 800, 800)), // 2 覆盖云端；1、3 云端多余 → 删除
            )
        )
        assertEquals(setOf("2", "3"), r.mergedFavorites.map { it.id }.toSet())
        assertEquals(listOf("2"), r.mergedHistory.map { it.series.id })
        assertEquals(setOf("2", "3"), r.favoritesToUpload.map { it.id }.toSet())
        // 云端收藏 id=1 本机没有 → 要求取消；历史 id=1、3 本机没有 → 要求软删除。
        assertEquals(listOf("1"), r.favoriteIdsToRemove)
        assertEquals(setOf("1", "3"), r.historyToRemove.map { it.series.id }.toSet())
    }

    @Test fun `墓碑编解码往返`() {
        val record = SyncRecord(
            mode = SyncMode.MERGE,
            lastSyncAt = 12345L,
            tombstones = listOf(
                SyncTombstone("1", 100, SyncTombstone.Kind.FAVORITE),
                SyncTombstone("2", 200, SyncTombstone.Kind.HISTORY),
            ),
        )
        val decoded = SyncRecord.decode(record.encode())
        assertEquals(SyncMode.MERGE, decoded.mode)
        assertEquals(12345L, decoded.lastSyncAt)
        assertEquals(2, decoded.tombstones.size)
        assertTrue(decoded.isTombstoned("1", SyncTombstone.Kind.FAVORITE))
        assertFalse(decoded.isTombstoned("1", SyncTombstone.Kind.HISTORY))
    }
}
