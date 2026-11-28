// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 云账号与本机数据的合并引擎（纯逻辑，无 IO、无 Android 依赖）。
 *
 * 三种开启模式：
 * - [SyncMode.MERGE]：双向合并（默认推荐）。收藏按 id 取并集、历史按 updatedAt 取新；
 *   本机有云端无 → 上传，云端有本机无 → 下载，命中墓碑的本机删除不被复活。
 * - [SyncMode.CLOUD_WINS]：丢弃本机，以账号为准。本机收藏/历史被云端全集替换，
 *   本机独有的项被丢弃；墓碑清空（本机删除语义让位于云端）。
 * - [SyncMode.LOCAL_WINS]：上传本机覆盖账号。本机全集上行到云端，云端本机没有的项
 *   从云端删除（收藏取消 / 历史软删除）；本机全集为最终写回结果。
 *
 * [SyncMode.OFF] 下不合并：本机保持原样，也不产生任何上传动作（账号数据只读隔离）。
 */
object SyncEngine {

    data class Input(
        val mode: SyncMode,
        val cloudFavorites: List<CloudFavorite>,
        val cloudHistory: List<CloudProgress>,
        val localFavorites: List<Series>,
        val localHistory: List<BackupProgress>,
        val record: SyncRecord,
    )

    data class Result(
        /** 合并后应写回本机的收藏全集。 */
        val mergedFavorites: List<Series>,
        /** 合并后应写回本机的历史全集。 */
        val mergedHistory: List<BackupProgress>,
        /** 需要上传到云端的新增收藏（本机有云端无）。 */
        val favoritesToUpload: List<Series>,
        /** 需要取消云端收藏的 seriesId（本机墓碑、云端仍存在）。 */
        val favoriteIdsToRemove: List<String>,
        /** 需要上传云端的进度（云端缺失，或本机更新）。 */
        val progressToUpload: List<CloudProgress>,
        /** 需要从云端软删除的历史（LOCAL_WINS 覆盖时，云端有本机无的项）。 */
        val historyToRemove: List<CloudProgress> = emptyList(),
        /** 各维度计数，供 UI 展示「已合并 N 条」。 */
        val downloadedFavorites: Int,
        val downloadedHistory: Int,
        val uploadedFavorites: Int,
        val uploadedHistory: Int,
    ) {
        val totalChanges: Int
            get() = downloadedFavorites + downloadedHistory + uploadedFavorites + uploadedHistory
    }

    fun merge(input: Input): Result = when (input.mode) {
        SyncMode.OFF -> offResult(input)
        SyncMode.MERGE -> mergeBoth(input)
        SyncMode.CLOUD_WINS -> cloudWins(input)
        SyncMode.LOCAL_WINS -> localWins(input)
    }

    /** 关闭态：保持本机原样，不下载、不上传、不删墓碑。 */
    private fun offResult(input: Input) = Result(
        mergedFavorites = input.localFavorites,
        mergedHistory = input.localHistory,
        favoritesToUpload = emptyList(),
        favoriteIdsToRemove = emptyList(),
        progressToUpload = emptyList(),
        downloadedFavorites = 0, downloadedHistory = 0,
        uploadedFavorites = 0, uploadedHistory = 0,
    )

    /** 以账号为准：本机收藏/历史被云端全集替换，本机独有项丢弃。 */
    private fun cloudWins(input: Input): Result {
        val cloudFav = input.cloudFavorites.map { it.series }
        val cloudHist = input.cloudHistory.map { it.toLocalProgress() }
        return Result(
            mergedFavorites = cloudFav,
            mergedHistory = cloudHist,
            favoritesToUpload = emptyList(),
            favoriteIdsToRemove = emptyList(),
            progressToUpload = emptyList(),
            downloadedFavorites = cloudFav.size,
            downloadedHistory = cloudHist.size,
            uploadedFavorites = 0,
            uploadedHistory = 0,
        )
    }

    /** 以本机为准：本机全集上行，云端多余的项从云端删除，本机全集为最终结果。 */
    private fun localWins(input: Input): Result {
        val favTombstones = input.record.tombstones.filter { it.kind == SyncTombstone.Kind.FAVORITE }
            .map { it.seriesId }.toSet()
        val histTombstones = input.record.tombstones.filter { it.kind == SyncTombstone.Kind.HISTORY }
            .map { it.seriesId }.toSet()

        val localFavById = input.localFavorites.associateBy { it.id }
        val localHistById = input.localHistory.associateBy { it.series.id }
        val cloudFavById = input.cloudFavorites.associateBy { it.series.id }
        val cloudHistById = input.cloudHistory.associateBy { it.series.id }

        // 本机全集（剔除墓碑项）即最终结果，也是要全量上行的集合。
        val finalFav = localFavById.filterKeys { it !in favTombstones }.values.toList()
        val finalHist = localHistById.filterKeys { it !in histTombstones }.values.toList()

        // 云端有、本机没有的项 → 从云端删除（覆盖语义）。
        // 命中墓碑的本机项已不在 final 里，云端若仍存在也一并删除。
        val favIdsToRemove = (cloudFavById.keys - finalFav.map { it.id }.toSet()).toList() +
            favTombstones.filter { it in cloudFavById }
        val historyToRemove =
            (cloudHistById.keys - finalHist.map { it.series.id }.toSet()).mapNotNull { cloudHistById[it] }

        return Result(
            mergedFavorites = finalFav,
            mergedHistory = finalHist,
            favoritesToUpload = finalFav,
            favoriteIdsToRemove = favIdsToRemove.distinct(),
            progressToUpload = finalHist.map { it.toCloudProgress() },
            historyToRemove = historyToRemove,
            downloadedFavorites = 0,
            downloadedHistory = 0,
            uploadedFavorites = finalFav.size,
            uploadedHistory = finalHist.size,
        )
    }

    /** 双向合并（默认）：并集，历史按 updatedAt 取新，墓碑阻止复活。 */
    private fun mergeBoth(input: Input): Result {
        val favTombstones = input.record.tombstones.filter { it.kind == SyncTombstone.Kind.FAVORITE }
            .associateBy { it.seriesId }
        val histTombstones = input.record.tombstones.filter { it.kind == SyncTombstone.Kind.HISTORY }
            .associateBy { it.seriesId }

        val cloudFavById = input.cloudFavorites.associateBy { it.series.id }
        val localFavById = input.localFavorites.associateBy { it.id }

        // ---- 收藏：并集，剔除墓碑 ----
        // 本机项若命中收藏墓碑 → 视为已删，不保留、不上传。
        val localFavAlive = localFavById.filterKeys { it !in favTombstones }
        val mergedFav = LinkedHashMap<String, Series>()
        var downloadedFav = 0
        var uploadedFav = 0
        val favToUpload = ArrayList<Series>()
        cloudFavById.forEach { (id, cf) ->
            if (id in favTombstones) return@forEach // 本机已删，云端项不下载
            mergedFav[id] = cf.series
            downloadedFav++ // 云端带来的一条（含两边都有的，计数按云端贡献）
        }
        localFavAlive.forEach { (id, series) ->
            if (mergedFav.containsKey(id)) return@forEach
            mergedFav[id] = series
            favToUpload += series
            uploadedFav++
        }
        // 云端命中收藏墓碑的项，需要推取消收藏。
        val favIdsToRemove = cloudFavById.keys.filter { it in favTombstones }

        // ---- 历史：并集，按 updatedAt 取新，剔除墓碑 ----
        val cloudHistById = input.cloudHistory.associateBy { it.series.id }
        val localHistById = input.localHistory.associateBy { it.series.id }
        val mergedHist = LinkedHashMap<String, BackupProgress>()
        var downloadedHist = 0
        var uploadedHist = 0
        val progressToUpload = ArrayList<CloudProgress>()

        val allIds = (cloudHistById.keys + localHistById.keys).distinct().filter { it !in histTombstones }
        allIds.forEach { id ->
            val cloud = cloudHistById[id]
            val local = localHistById[id]
            when {
                cloud == null && local != null -> {
                    // 本机有云端无 → 保留本机并上传进度。
                    mergedHist[id] = local
                    progressToUpload += local.toCloudProgress()
                    uploadedHist++
                }
                cloud != null && local == null -> {
                    // 云端有本机无 → 下载。
                    mergedHist[id] = cloud.toLocalProgress()
                    downloadedHist++
                }
                cloud != null && local != null -> {
                    if (cloud.updatedAt > local.updatedAt) {
                        // 云端更新 → 用云端覆盖本机。
                        mergedHist[id] = cloud.toLocalProgress()
                        downloadedHist++
                    } else {
                        // 本机更新或相同 → 保留本机；仅本机明显更新时上行。
                        mergedHist[id] = local
                        if (local.updatedAt > cloud.updatedAt) {
                            progressToUpload += local.toCloudProgress()
                            uploadedHist++
                        }
                    }
                }
            }
        }

        return Result(
            mergedFavorites = mergedFav.values.toList(),
            mergedHistory = mergedHist.values.toList(),
            favoritesToUpload = favToUpload,
            favoriteIdsToRemove = favIdsToRemove,
            progressToUpload = progressToUpload,
            downloadedFavorites = downloadedFav,
            downloadedHistory = downloadedHist,
            uploadedFavorites = uploadedFav,
            uploadedHistory = uploadedHist,
        )
    }

    private fun BackupProgress.toCloudProgress() = CloudProgress(
        series = series,
        episodeId = episodeId,
        episodeIndex = episodeIndex,
        position = position,
        duration = duration,
        updatedAt = updatedAt,
    )

    private fun CloudProgress.toLocalProgress() = BackupProgress(
        series = series,
        episodeId = episodeId,
        episodeIndex = episodeIndex,
        position = position,
        duration = duration,
        // 云端没有 completed 概念：播到接近结尾视为已看完，与本机口径一致。
        completed = duration > 0 && position >= duration * 0.95,
        updatedAt = updatedAt,
    )
}
