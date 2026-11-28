// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

object PlaybackQuality {
    const val AUTO_HIGH = PlaybackQualityMode.AUTO_HIGH
    const val AUTO_COMPAT = PlaybackQualityMode.AUTO_COMPAT
    // 「自动（兼容优先）」已从设置选项移除；历史存档里的 -2 不在 options 内，
    // 会被 normalize 自动迁移到 AUTO_HIGH，新安装也默认 AUTO_HIGH。
    // 档位用官方标称值（和 1080P 一样），显示与存储都保持一致。
    val options=listOf(360,480,540,720,1080,AUTO_HIGH)
    fun normalize(value: Int)=value.takeIf { it in options } ?: AUTO_HIGH
    fun label(value: Int)=when(normalize(value)) {
        AUTO_HIGH -> "自动（高清优先）"
        else -> "${normalize(value)}P"
    }

    /**
     * 标称档位 → 取流请求值。
     *
     * 官方接口按「视频短边」选档，且各档短边会在标称值附近波动（例如 1080p 实际短边恒为 1080，
     * 但 540p 片源的实际短边约 576）。JS 的选档规则是：请求值 < 1080 时优先取短边「小于请求值」的档，
     * 若直接传标称 540，576 档（>540）会被判到「更高档」而落空，错选成 480p。
     *
     * 因此只把 540 上移到一个能容纳短边波动（约 560~600）的请求值 600；
     * 其它档和 1080P 一样原样透传，靠接口自身的就近匹配吸收波动，不固定死真实短边。
     */
    fun streamRequest(value: Int): Int = when (normalize(value)) {
        540 -> 600
        else -> value
    }
}

/** One automatic recovery budget per episode; only an explicit user retry resets it. */
class RecoveryBudget {
    var attempts=0; private set
    val remaining get()=attempts<3
    val delayMillis get()=listOf(1000L,2500L,5000L).getOrNull(attempts)
    fun consume(): Boolean { if(!remaining) return false; attempts++; return true }
    fun reset() { attempts=0 }
}

data class ResumeTarget(val index: Int,val position: Long)
object ResumePlayback {
    fun target(episodes: List<String>,episodeId: String?,position: Long,completed: Boolean): ResumeTarget {
        require(episodes.isNotEmpty())
        val index=episodes.indexOf(episodeId)
        if(index<0) return ResumeTarget(0,0)
        return if(completed) ResumeTarget((index+1).coerceAtMost(episodes.lastIndex),0)
            else ResumeTarget(index,position.coerceAtLeast(0))
    }
}

/** Seen count is a high-water mark, so temporary catalog shrinkage is not a new update. */
data class FavoriteUpdate(val total: Int,val seen: Int,val checkedAt: Long,
    val newSeasonId: String? = null,val newSeasonTitle: String? = null,val ackedSeasonId: String? = null) {
    val added get()=(total-seen).coerceAtLeast(0)
    val hasNewSeason get()=!newSeasonId.isNullOrBlank()
    val hasUpdate get()=added>0 || hasNewSeason
    fun observe(count: Int,now: Long,acknowledge: Boolean=false): FavoriteUpdate {
        require(count>0)
        if(now<checkedAt) return this
        return FavoriteUpdate(count,if(acknowledge) maxOf(seen,count) else seen,now,
            if(acknowledge) null else newSeasonId,if(acknowledge) null else newSeasonTitle,
            if(acknowledge) newSeasonId ?: ackedSeasonId else ackedSeasonId)
    }
    fun observeNewSeason(seasonId: String?,seasonTitle: String?,now: Long): FavoriteUpdate {
        if(now<checkedAt) return this
        if(seasonId.isNullOrBlank() || !seasonId.matches(Regex("[0-9]{1,30}"))) return this
        // A season the user already saw must not re-surface on the next periodic check.
        if(seasonId==newSeasonId || seasonId==ackedSeasonId) return this
        return FavoriteUpdate(total,seen,now,seasonId,seasonTitle?.ifBlank { null },ackedSeasonId)
    }
    fun json()=JSONObject().put("total",total).put("seen",seen).put("checkedAt",checkedAt)
        .put("newSeasonId",newSeasonId).put("newSeasonTitle",newSeasonTitle).put("ackedSeasonId",ackedSeasonId)
    companion object {
        fun first(count: Int,now: Long): FavoriteUpdate { require(count>0); return FavoriteUpdate(count,count,now) }
        fun decode(value: JSONObject?)=runCatching {
            require(value!=null)
            val total=value.getInt("total"); val seen=value.getInt("seen"); val time=value.getLong("checkedAt")
            require(total>0 && seen>0 && time>=0)
            val seasonId=value.optString("newSeasonId").ifBlank { null }
            val seasonTitle=value.optString("newSeasonTitle").ifBlank { null }
            val ackedId=value.optString("ackedSeasonId").ifBlank { null }
            require(seasonId==null || seasonId.matches(Regex("[0-9]{1,30}")))
            require(ackedId==null || ackedId.matches(Regex("[0-9]{1,30}")))
            FavoriteUpdate(total,seen,time,seasonId,seasonTitle,ackedId)
        }.getOrNull()
    }
}
