// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.data

import com.hongguotv.core.BackupProgress
import com.hongguotv.core.ContentType
import com.hongguotv.core.Detail
import com.hongguotv.core.RankBoard
import com.hongguotv.core.RecentSearch
import com.hongguotv.core.SeasonEntry
import com.hongguotv.core.Series
import com.hongguotv.core.TheaterFilterGroup
import com.hongguotv.nativeapp.ui.nav.CollectionKind

enum class ShelfStyle { RESUME, POSTER }

/** 播放页可自定义的四个遥控器按键。 */
enum class RemoteKey(val label: String, val fallback: String) {
    UP("上键", "previous"),
    DOWN("下键", "next"),
    CONFIRM("确认键", "play_pause"),
    MENU("菜单键", "menu"),
}

fun videoInfoLabel(mode: Int): String = when (mode) {
    1 -> "简要（分辨率）"
    2 -> "详细（码率与帧率）"
    else -> "关闭"
}

data class ShelfEntry(
    val series: Series,
    val label: String? = null,
    val progress: Float? = null,
    val rank: Int? = null,
)

data class Shelf(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val style: ShelfStyle = ShelfStyle.POSTER,
    val entries: List<ShelfEntry> = emptyList(),
    val collection: CollectionKind? = null,
)

data class HomeState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val hero: List<Series> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
    val heroIndex: Int = 0,
    val updatedAt: Long = 0L,
) {
    val visibleShelves: List<Shelf> get() = shelves.filter { it.entries.isNotEmpty() }
}

data class TheaterState(
    val type: ContentType = ContentType.SHORT,
    val groups: List<TheaterFilterGroup> = emptyList(),
    val selection: Map<String, String> = emptyMap(),
    val items: List<Series> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = true,
    // 未开始加载 ≠ 正在加载：默认 false，否则首帧会被当成「加载中」，
    // 而调用方又用 !loading 作为发起加载的条件，结果首屏永远转圈。
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

data class RankingState(
    val board: RankBoard = RankBoard.TOTAL,
    val items: List<Series> = emptyList(),
    val heat: Map<String, String> = emptyMap(),
    val page: Int = 1,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val updatedText: String = "",
)

data class SearchState(
    val query: String = "",
    val type: ContentType? = null,
    val results: List<Series> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val history: List<RecentSearch> = emptyList(),
    val hot: List<Series> = emptyList(),
    val hotLoading: Boolean = true,
    val searching: Boolean = false,
    val page: Int = 1,
    val hasMore: Boolean = true,
    val error: String? = null,
    val submitted: Boolean = false,
    val keyboardVisible: Boolean = false,
)

data class DetailState(
    val loading: Boolean = true,
    val series: Series? = null,
    val detail: Detail? = null,
    val error: String? = null,
    val favorite: Boolean = false,
    val synopsisExpanded: Boolean = false,
    val resumeIndex: Int = 0,
    val resumePosition: Long = 0L,
    val resumeProgress: Float? = null,
    val seasons: List<SeasonEntry> = emptyList(),
)

data class LibraryState(
    val favorites: List<Series> = emptyList(),
    val history: List<BackupProgress> = emptyList(),
    val later: List<Series> = emptyList(),
    val favoriteLabels: Map<String, String> = emptyMap(),
    // 每部剧最新一集是否已看完（history.completed），供收藏页显示「已看完」角标。
    val completedIds: Set<String> = emptySet(),
    val version: Int = 0,
    val groupFilter: String? = null,
)

data class SettingsSnapshot(
    val quality: Int,
    val speed: Float,
    val autoNext: Boolean,
    val autoSeason: Boolean,
    val danmaku: Boolean,
    val showClock: Boolean,
    val frameMode: String,
    // ---- 弹幕细化 ----
    val danmakuOpacity: Int = 100,
    val danmakuFont: Int = 2,
    val danmakuSpeed: Int = 2,
    val danmakuRows: Int = 2,
    /** 0=默认 SurfaceView，1=普通 View 实验。 */
    val danmakuRendererMode: Int = 0,
    val danmakuColor: Int = 0,
    val blockedWords: List<String> = emptyList(),
    // ---- 播放增强 ----
    val startupAutoplay: Boolean = false,
    val seamlessPlayback: Boolean = false,
    val seekStepSeconds: Int = 10,
    val longPressSpeed: Float = 3f,
    // ---- 画面 ----
    val posterColumns: Int = 0,
    val videoInfoMode: Int = 0,
    val showNetworkSpeed: Boolean = false,
    /** 屏幕最底部常驻一条极细进度条；默认关闭，不影响观感。 */
    val persistentProgress: Boolean = false,
    /** 是否在页面顶部显示可点击的返回箭头；默认关闭，遥控器按返回键即可返回。 */
    val showScreenBack: Boolean = false,
    val appIconVariant: String = "i",
    // ---- 按键 ----
    val remoteUp: String = "previous",
    val remoteDown: String = "next",
    val remoteConfirm: String = "play_pause",
    val remoteMenu: String = "menu",
    val menuKeyAction: String = "panel",
    // ---- 网络 ----
    val dnsProvider: String = "tencent",
    val dnsCustom: String = "",
) {
    /** 0 表示跟随屏幕宽度自动计算。 */
    val effectivePosterColumns: Int get() = posterColumns.coerceIn(0, 8)
}

/** 账号页的两种登录方式。 */
enum class LoginMode { QR, PHONE }

data class AccountState(
    val signedIn: Boolean = false,
    val nickname: String = "",
    val avatar: String = "",
    val qrBitmap: ByteArray? = null,
    val qrLoading: Boolean = false,
    val qrHint: String = "使用红果 App 扫码登录",
    val busy: Boolean = false,
    val message: String? = null,
    val cloudFavorites: Int = 0,
    val cloudHistory: Int = 0,
    // ---- 手机验证码 ----
    val mode: LoginMode = LoginMode.QR,
    val phone: String = "",
    val code: String = "",
    val codeSent: Boolean = false,
    val phoneMasked: String = "",
    val sending: Boolean = false,
    val verifying: Boolean = false,
    val countdown: Int = 0,
    val phoneError: String? = null,
) {
    val phoneValid: Boolean get() = PHONE_PATTERN.matches(phone)
    val codeValid: Boolean get() = code.length in 4..6

    companion object {
        private val PHONE_PATTERN = Regex("1[3-9]\\d{9}")
    }
}

/** 投播阶段。界面文案、状态灯与步骤高亮全部由它推导，不再散落布尔量。 */
enum class CastPhase { OFF, STARTING, READY, RECEIVING, FAILED }

/** 匹配成功后交给界面的一次性跳转请求：谁消费谁清空，避免重复进详情。 */
data class CastOpen(
    val series: Series,
    val episodeId: String? = null,
    val episodeLabel: String = "",
)

data class CastState(
    val phase: CastPhase = CastPhase.OFF,
    /** 手机访问地址（含 http://），二维码与地址栏共用同一个值。 */
    val url: String = "",
    val message: String = "正在启动投播服务…",
    /** 识别进度、失败原因等第二行说明。 */
    val detail: String? = null,
    val candidates: List<Series> = emptyList(),
    /** 本轮成功收到的分享条数：0 表示还没人发过。 */
    val received: Int = 0,
    /** 最近一次识别出的剧名，用于「已找到《X》，正在打开…」。 */
    val lastTitle: String = "",
    val open: CastOpen? = null,
    val port: Int = 0,
) {
    val running: Boolean
        get() = phase == CastPhase.STARTING || phase == CastPhase.READY || phase == CastPhase.RECEIVING
}

data class AppState(
    val booted: Boolean = false,
    val bootError: String? = null,
    val home: HomeState = HomeState(),
    val theater: TheaterState = TheaterState(),
    val ranking: RankingState = RankingState(),
    val search: SearchState = SearchState(),
    val detail: DetailState = DetailState(),
    val library: LibraryState = LibraryState(),
    val account: AccountState = AccountState(),
    val cast: CastState = CastState(),
    val script: ScriptUpdateState = ScriptUpdateState(),
    /** 首次启动的脚本准备态：APK 不带 main.js，全新安装要先下载一次。 */
    val scriptSetup: ScriptSetupState = ScriptSetupState(),
    val toast: String? = null,
    /** 设置项被改写时自增，让设置页快照重新计算。 */
    val settingsRevision: Int = 0,
)

/**
 * 首次启动的脚本准备态。只有「本地没有任何可用脚本」时才会亮起：
 * 全新安装、或当前版与上一版都损坏。下载成功后复位，主界面才开始渲染。
 */
data class ScriptSetupState(
    val preparing: Boolean = false,
    val error: String? = null,
) {
    val active: Boolean get() = preparing || error != null
}

/**
 * 数据脚本（main.js）的热更新状态。全程自动：启动时检查、后台下载、
 * 校验通过后立即生效（只有播放中会推迟到退出播放页），用户不需要做任何操作。
 */
data class ScriptUpdateState(
    /** 运行中脚本的构建时间，如「2026-11-24 03:38」。 */
    val version: String = "",
    /** 运行中脚本 sha256 前 8 位，用于肉眼区分两版。 */
    val digest: String = "",
    /** true = 来自在线更新（含上一版兜底），false = APK 内置。 */
    val fromUpdate: Boolean = false,
    val checking: Boolean = false,
    /** 已落盘、等待切换的版本号；切换完成后清空。 */
    val pendingVersion: String = "",
    val checkedAt: Long = 0L,
    val installedAt: Long = 0L,
    val lastError: String? = null,
)
