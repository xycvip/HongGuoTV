// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.ui.data

import com.hongguotv.core.ActorRankBoard
import com.hongguotv.core.ActorRankEntry
import com.hongguotv.core.BackupProgress
import com.hongguotv.core.CloudReservation
import com.hongguotv.core.ContentType
import com.hongguotv.core.Detail
import com.hongguotv.core.RankBoard
import com.hongguotv.core.RankKind
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
    3 -> "调试模式（全屏诊断）"
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
    /** 内容类型：综合 / 真人剧 / 漫剧 / AI剧。 */
    val board: RankBoard = RankBoard.TOTAL,
    /** 榜单口径：热播 / 推荐 / 新剧 / 臻果。 */
    val kind: RankKind = RankKind.HEAT,
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

/**
 * 演员档案弹窗的数据。
 *
 * [actor] 非空即弹窗可见；关闭时由 ViewModel 清空，避免切换到另一部剧时
 * 新详情页继承上一位演员的弹窗。
 *
 * [nextOffset] 是官方游标（已消耗行数），不是页码：加载更多只能原样回传，
 * 用 page × count 反推会在上游丢弃畸形行时越翻越偏（实测会漏条目）。
 */
data class ActorState(
    val actor: com.hongguotv.core.Actor? = null,
    val items: List<Series> = emptyList(),
    val total: Int = 0,
    val nextOffset: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

/**
 * 演员榜页面（剧星榜 / 星光榜）。
 *
 * [nextOffset] 是官方游标（已消耗行数），[sessionId] 必须随翻页回传：
 * 实测不带会话直接翻第 2 页，拿回来的还是第 1 页那 20 个人。
 */
data class ActorRankState(
    val board: ActorRankBoard = ActorRankBoard.DRAMA,
    val items: List<ActorRankEntry> = emptyList(),
    val nextOffset: Int = 0,
    val sessionId: String = "",
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

/**
 * 详情页「发布作者」区块状态。
 *
 * 从 creatorList 里挑出作者本人（relativeType=3），再按需拉 creatorProfile 补全档案，
 * 作品列表来自 creatorWorks，游标 [nextCursor] 原样回传。
 */
data class CreatorState(
    val creator: com.hongguotv.core.Creator? = null,
    val works: List<Series> = emptyList(),
    val nextCursor: String = "",
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loadingWorks: Boolean = false,
    val error: String? = null,
)

/** 详情页「剧评」区块状态。游标 [cursor] 原样回传。 */
data class ReviewState(
    val items: List<com.hongguotv.core.Review> = emptyList(),
    val total: Int = 0,
    val cursor: String = "",
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

data class LibraryState(
    val favorites: List<Series> = emptyList(),
    val history: List<BackupProgress> = emptyList(),
    val later: List<Series> = emptyList(),
    val favoriteLabels: Map<String, String> = emptyMap(),
    // 每部剧最新一集是否已看完（history.completed），供收藏页显示「已看完」角标。
    val completedIds: Set<String> = emptySet(),
    /** 预约列表来自云端账号片单；未登录时为空。 */
    val reservations: List<CloudReservation> = emptyList(),
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
    /** 0=隔帧绘制，1=逐帧绘制。 */
    val danmakuFrameRateMode: Int = 0,
    /** 合并重复弹幕：同一文本 10 秒内只显示一条。 */
    val danmakuMergeRepeat: Boolean = false,
    val danmakuColor: Int = 0,
    val blockedWords: List<String> = emptyList(),
    // ---- 播放增强 ----
    val startupAutoplay: Boolean = false,
    val seamlessPlayback: Boolean = false,
    /** 自动降低分辨率：非 8 对齐的 1080p 档自动降到低分辨率，默认关闭。 */
    val autoDowngradeNonAligned: Boolean = false,
    val seekStepSeconds: Int = 10,
    // ---- 画面 ----
    val posterColumns: Int = 0,
    /** 首页大海报的取数来源，见 [com.hongguotv.nativeapp.HeroSource]，默认综合。 */
    val heroSource: String = "mixed",
    val videoInfoMode: Int = 0,
    val showNetworkSpeed: Boolean = false,
    /** 屏幕最底部常驻一条极细进度条；默认关闭，不影响观感。 */
    val persistentProgress: Boolean = false,
    /** 是否在页面顶部显示可点击的返回箭头；默认关闭，遥控器按返回键即可返回。 */
    val showScreenBack: Boolean = false,
    // ---- 按键 ----
    val remoteUp: String = "previous",
    val remoteDown: String = "next",
    val remoteConfirm: String = "play_pause",
    val remoteMenu: String = "menu",
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
    // ---- 云同步 ----
    /** 同步开关：默认关闭，账号数据与本机隔离；开启后双向合并。 */
    val syncEnabled: Boolean = false,
    /** 正在合并/上传中，UI 显示进度且禁止重复触发。 */
    val syncBusy: Boolean = false,
    /** 同步结果提示，如「已合并 12 条 · 上传 3 条」。 */
    val syncStatus: String = "",
    // ---- 手机验证码 ----
    val mode: LoginMode = LoginMode.QR,
    /** 手机号区号：+86 时号码按国内 11 位校验，其它区号按 5-15 位国际号码校验。 */
    val countryCode: String = "+86",
    val phone: String = "",
    val code: String = "",
    val codeSent: Boolean = false,
    val phoneMasked: String = "",
    val sending: Boolean = false,
    val verifying: Boolean = false,
    val countdown: Int = 0,
    val phoneError: String? = null,
) {
    val phoneValid: Boolean
        get() = if (countryCode == "+86") PHONE_PATTERN.matches(phone) else INTL_PATTERN.matches(phone)
    val codeValid: Boolean get() = code.length in 4..8

    companion object {
        private val PHONE_PATTERN = Regex("1[3-9]\\d{9}")
        private val INTL_PATTERN = Regex("[0-9]{5,15}")
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

/** 定时关闭的计时方式。OFF 表示未设置。 */
enum class SleepMode { OFF, MINUTES, EPISODES }

/** 定时到点后的动作：暂停播放（保留进度）或彻底退出应用。 */
enum class SleepAction { PAUSE, EXIT }

/**
 * 定时关闭状态。应用级持有，切剧/跨季连播都不重置；
 * 只有用户主动关闭、到点触发、或切换计时方式时才改变。
 */
data class SleepTimerUiState(
    val mode: SleepMode = SleepMode.OFF,
    /** 分钟模式的分钟数，或集数模式的集数。 */
    val value: Int = 0,
    val action: SleepAction = SleepAction.PAUSE,
    /** 面板底部/无障碍朗读用的完整描述，例如「30 分钟后暂停播放」。 */
    val summaryText: String = "",
    /** 右上角临界倒计时文案：分钟<=60s 逐秒到 0、集数<=3 逐集，其余为空（不常驻）。 */
    val countdownText: String = "",
    val active: Boolean = false,
) {
    val badgeOn: Boolean get() = active
}

data class AppState(
    val booted: Boolean = false,
    val bootError: String? = null,
    val home: HomeState = HomeState(),
    val theater: TheaterState = TheaterState(),
    val ranking: RankingState = RankingState(),
    val search: SearchState = SearchState(),
    val detail: DetailState = DetailState(),
    val actor: ActorState = ActorState(),
    val actorRank: ActorRankState = ActorRankState(),
    /** 详情页发布作者（独立于演员弹窗）。 */
    val creator: CreatorState = CreatorState(),
    /** 详情页剧评。 */
    val reviews: ReviewState = ReviewState(),
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
    /** 运行中脚本的版本号，如「v1.0.0-20261128」；清单未带版本号时回退构建时间。 */
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
    /** 手动点击「接口版本」后的临时反馈，几秒后自动清除。 */
    val manualHint: String? = null,
    /** 提示序号，防止旧的延时把新提示清掉。 */
    val manualHintSeq: Int = 0,
)
