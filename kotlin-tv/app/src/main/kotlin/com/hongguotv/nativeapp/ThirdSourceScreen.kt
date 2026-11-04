// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hongguotv.core.PhoneField
import com.hongguotv.core.PhoneForm
import com.hongguotv.core.PlaybackSpeed
import com.hongguotv.core.ThirdSourceConfig
import java.util.concurrent.Executors

/** 三方片源页：自成体系的「店中店」——浏览/详情/播放全部独立，与红果内容体系零交集。
 *  生命周期由 MainActivity 委托：show/onBack/onKey/pause/stop/release。 */
// 播放器用到 media3 未稳定 API（PlayerView/AspectRatioFrameLayout/SeekParameters），整类显式接受
@androidx.annotation.OptIn(UnstableApi::class)
class ThirdSourceScreen(private val activity: Activity, private val rebuild: () -> Unit, private val backupRequest: () -> Unit = {}) {

    private companion object {
        /** 三方片源免责声明：空态与源管理页常驻展示。 */
        const val SOURCE_DISCLAIMER = "第三方 CMS 接口由用户自行添加，仅为技术交流与学习用途，应用不提供、不存储、不传播任何视频内容，内容合法性由对应源站负责。"
        /** 聚合搜索单次最多参与的源数：当前源优先，独立线程池并发，避免被慢源拖成多轮。 */
        const val AGGREGATE_LIMIT = 6
        /** 探活缓存有效期：30 分钟内的结果直接复用，冷启动不再全量请求。 */
        const val PROBE_TTL_MS = 30 * 60_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val muted get() = TvStyle.muted
    private val store = ThirdStore(activity)
    private val progressRecorder by lazy { ThirdSourceProgressRecorder(store, backupRequest = { backupRequest() }) }
    private val playbackSkipController by lazy {
        ThirdSourcePlaybackSkipController(
            activity = activity,
            store = store,
            tools = tvTools,
            currentDetail = { detail },
            currentSourceApi = { detailSource?.api },
            currentPlayer = { player },
            currentGroup = { playbackState.group },
            currentEpisode = { playbackState.episode },
            jumpEpisode = ::jumpEpisode,
            showOverlay = { showOverlay() }
        )
    }
    private val client = ThirdCmsClient()
    private val artwork = ArtworkLoader(activity)
    private val tvTools = TvTools(activity)
    private val ui = ThirdSourceUi(activity)
    private val gridRenderer by lazy { ThirdSourceGridRenderer(activity, ui, artwork) }
    private val executor = Executors.newFixedThreadPool(3)
    private val probeExecutor = Executors.newFixedThreadPool(2)
    /** 聚合搜索独立并发：同轮所有源同时发出，慢源不会挤占浏览/详情请求的线程。 */
    private val searchExecutor = Executors.newFixedThreadPool(AGGREGATE_LIMIT)
    private val catalogRequests = ThirdSourceCatalogRequestCoordinator(client, executor, main)
    private val searchRequests = ThirdSourceSearchRequestCoordinator(client, searchExecutor, main)
    private val speedTests = ThirdSourceSpeedTestCoordinator(client, probeExecutor, main)
    private val suggestionRequests = ThirdSourceSuggestionRequestCoordinator(client, executor, main)
    private val detailRequests = ThirdSourceDetailRequestCoordinator(client, executor, main)
    private val probeRequests = ThirdSourceProbeRequestCoordinator(client, store, probeExecutor, main)
    private val importRequests = ThirdSourceImportRequestCoordinator(client, executor, main)
    private val addSourceDialog by lazy {
        ThirdSourceAddSourceDialog(
            activity = activity,
            ui = ui,
            tvTools = tvTools,
            probeRequests = probeRequests,
            store = store,
            sources = { sources },
            probes = probes,
            generation = { generation },
            resetBrowseState = ::resetBrowseState,
            backupRequest = backupRequest,
            rebuild = rebuild,
        )
    }
    private val importDialog by lazy {
        ThirdSourceImportDialog(
            activity = activity,
            ui = ui,
            tvTools = tvTools,
            requests = importRequests,
            generation = { generation },
            onParsed = ::showImportChecklist,
        )
    }
    private val playbackKeyController = ThirdSourcePlaybackKeyController()
    private val navigationState = ThirdSourceNavigationState()
    private var generation: Int by navigationState::generation
    private var mode: ThirdSourceScreenMode by navigationState::mode
    var topFocusId = View.NO_ID
        private set

    // ---- 浏览态 ----
    private val sourceRegistry = ThirdSourceRegistryState()
    private var sources: List<ThirdSource> by sourceRegistry::sources
    private var currentId: String? by sourceRegistry::currentId
    private val probes: MutableMap<String, ThirdSourceProbeState> by sourceRegistry::probes
    /** 最近一次测速耗时（毫秒），-1 表示该源不可用；不落盘。 */
    private val latencyMs: MutableMap<String, Long> by sourceRegistry::latencyMs
    private var speedTesting: Boolean by sourceRegistry::speedTesting
    private val capsuleDots = HashMap<String, View>()
    private val catalogPageState = ThirdSourceCatalogPageState()
    private var categories: List<ThirdCategory> by catalogPageState::categories
    private var page: Int by catalogPageState::page
    private var hasMore: Boolean by catalogPageState::hasMore
    private var videos: MutableList<ThirdVideo> by catalogPageState::videos
    private var loading: Boolean by catalogPageState::loading
    private var loadingMore: Boolean by catalogPageState::loadingMore
    // ---- 观看历史/收藏展示数据 ----
    private val libraryState = ThirdSourceLibraryState()
    /** 浏览态网格里最近聚焦的卡片（显示用视频 id）：进详情/播放返回时把焦点恢复到这张卡片上。 */
    private var browseFocusKey: String? = null
    private var loadError: String? = null
    private var searchWord: String? = null
    // 浏览态聚合搜索结果与跨源命中映射，独立于全屏搜索页的输入/分页状态
    private val browseSearchState = ThirdSourceBrowseSearchState()
    private var searchStatusView: TextView? = null
    // 搜索页（独立 ThirdSourceScreenMode.SEARCH）：输入、联想、分页状态与浏览态结果分离
    private val searchPageState = ThirdSourceSearchPageState()
    private val suggestDebounce = Runnable { runSuggest() }
    // 搜索筛选（客户端筛选，CMS 搜索接口不支持多维条件）
    private var probeSummary: TextView? = null
    private var gridStatus: TextView? = null
    private var gridAdapter: ThirdSourceGridAdapter? = null
    private var gridRecycler: RecyclerView? = null
    private var sourceButtons: List<View> = emptyList()

    // ---- 详情态 ----
    private val detailSessionState = ThirdSourceDetailSessionState()
    private var detail: ThirdDetail? by detailSessionState::detail
    /** 详情来自哪个源：聚合搜索结果/收藏/换源后不再等于当前浏览源。 */
    private var detailSource: ThirdSource? by detailSessionState::source
    private var detailGroup: Int by detailSessionState::group

    // ---- 播放态 ----
    private var player: ExoPlayer? = null
    private val playerViews = ThirdSourcePlayerViews()
    private var playerHost: FrameLayout? by playerViews::playerHost
    private var playerView: PlayerView? by playerViews::playerView
    private var playLoading: TextView? by playerViews::playLoading
    private var playErrorText: TextView? by playerViews::playErrorText
    private var overlay: View? by playerViews::overlay
    private var progressFill: View? by playerViews::progressFill
    private var progressThumb: View? by playerViews::progressThumb
    private var progressBubble: TextView? by playerViews::progressBubble
    private var seekBarView: View? by playerViews::seekBarView
    private var playTime: TextView? by playerViews::playTime
    private val playbackUiState = ThirdSourcePlaybackUiState()
    private var overlayVisible: Boolean by playbackUiState::overlayVisible
    // 平滑快进快退预览状态独立管理；页面只负责将状态应用到 ExoPlayer。
    private val scrubState = ThirdSourceScrubState()
    /** ExoPlayer 最近一次带宽估计（bps）：缓冲提示用它显示实时网速。 */
    private var lastBandwidthBps: Long by playbackUiState::lastBandwidthBps
    /** 当前详情对应的播放会话选择、待起播指令与备用线路尝试记录。 */
    private val playbackState = ThirdSourcePlaybackSessionState()
    private val hideOverlayRun = Runnable { hideOverlay() }
    private val saveRun = Runnable { saveProgressNow(); scheduleSave() }
    private val uiTick = Runnable { updatePlayUi() }
    /** 平滑快进快退节奏器：步长随按住时长爬坡（起步 5 秒、每秒加 10 秒、封顶 60 秒）。 */
    private val scrubTick = object : Runnable {
        override fun run() {
            val instance = player
            if(!scrubState.active || instance == null) return
            instance.seekTo(scrubState.advance(android.os.SystemClock.uptimeMillis(), instance.duration))
            updatePlayUi()
            main.postDelayed(this, 180L)
        }
    }
    private val playerSessionFactory by lazy {
        ThirdSourcePlayerSessionFactory(activity, ui, playerListener) { bitrate ->
            lastBandwidthBps = bitrate
            if(playLoading?.visibility == View.VISIBLE) showBuffering()
        }
    }

    private val playerListener = ThirdSourcePlayerListener(
        isPlayScreen = { mode == ThirdSourceScreenMode.PLAY && !scrubState.active },
        onBuffering = ::showBuffering,
        onReady = {
            playLoading?.visibility = View.GONE
            updatePlayUi()
        },
        onEnded = {
            keepScreenOn(false)
            showOverlay()
        },
        onPlayingChanged = ::keepScreenOn,
        onError = ::handlePlayerError
    )

    private fun handlePlayerError(error: PlaybackException) {
        // 线路失效时先自动换下一条线路，全部试过才提示手动换集
        val data = detail
        val next = data?.let { current ->
            ThirdPlaybackInteractionRules.nextFallbackGroup(
                current.groups.size, playbackState.group, playbackState.attemptedFallbackGroups
            ) { index -> current.groups[index].second.isNotEmpty() }
        }
        if(mode == ThirdSourceScreenMode.PLAY && next != null) {
            playbackState.markFallbackAttempt(playbackState.group)
            val position = player?.currentPosition ?: 0L
            playbackState.queuePlay(next, playbackState.episode, position, autoplay = true)
            playbackState.episode = playbackState.episode.coerceAtMost((data?.groups?.getOrNull(next)?.second?.size ?: 1) - 1)
            Toast.makeText(activity, "播放失败，已自动切换到「${data?.groups?.getOrNull(next)?.first.orEmpty()}」", Toast.LENGTH_SHORT).show()
            // 不在错误回调里直接重建：先让回调返回，下一帧再释放旧的播放器实例
            main.post { rebuild() }
        } else {
            playErrorText?.text = "该集播放失败，可换一集或切换线路"
            showOverlay()
        }
    }

    /** 播放期间保持屏幕常亮，避免电视空闲十几分钟后弹出系统屏保；暂停/结束/退出即释放。 */
    private fun keepScreenOn(on: Boolean) {
        if(on) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** 缓冲提示文案：已知带宽时附上实时网速，让用户直观判断是源慢还是本地网络问题。 */
    private fun bufferText(): String = ThirdPlaybackInteractionRules.bufferText(lastBandwidthBps)

    private fun showBuffering() {
        playLoading?.let { it.text = bufferText(); it.visibility = View.VISIBLE }
    }

    // ================= 生命周期 =================

    fun show(container: LinearLayout) {
        generation++; artwork.cancelPage()
        sources = store.ensureSources()
        currentId = store.currentId()?.takeIf { id -> sources.any { it.id == id } }
            ?: sources.firstOrNull()?.also { store.setCurrent(it.id) }?.id
        scheduleProbes()
        when {
            mode == ThirdSourceScreenMode.PLAY && detail != null && detailSource != null -> {
                val pending = playbackState.consumePendingPlay()
                if(pending != null) attachPlayer(pending.first, pending.second)
                else attachPlayer(playbackState.consumeLastPosition(), false)
            }
            mode == ThirdSourceScreenMode.DETAIL && detail != null -> renderDetail(container)
            mode == ThirdSourceScreenMode.SEARCH -> renderSearch(container)
            mode == ThirdSourceScreenMode.SOURCES -> renderSources(container)
            mode == ThirdSourceScreenMode.HISTORY -> renderHistory(container)
            mode == ThirdSourceScreenMode.FAVORITE -> renderFavorites(container)
            else -> { mode = ThirdSourceScreenMode.BROWSE; loading = false; loadingMore = false; renderBrowse(container) }
        }
        // 重建时系统可能在首帧布局阶段清掉焦点（片库页同样用 post 兜底）：下一帧再补一次，
        // 否则遥控器会出现「按了没反应」的失焦状态
        val focusId = topFocusId
        if(focusId != View.NO_ID) container.post {
            if(container.isAttachedToWindow) container.findViewById<View>(focusId)?.requestFocus()
        }
    }

    /** 返回：播放→详情→浏览（搜索页直接回浏览）；浏览态交还 MainActivity（导航焦点/退出确认）。 */
    fun onBack(): Boolean {
        val from = mode
        val target = navigationState.backTarget(searchWord != null, detailSessionState.returnMode) ?: return false
        when(from) {
            ThirdSourceScreenMode.PLAY -> {
                saveProgressNow()
                mode = target
                releasePlayback()
            }
            ThirdSourceScreenMode.DETAIL -> {
                detailSessionState.returnMode = ThirdSourceScreenMode.BROWSE
                mode = target
            }
            ThirdSourceScreenMode.SEARCH -> {
                searchWord = null
                mode = target
            }
            ThirdSourceScreenMode.SOURCES -> {
                sourceRegistry.batchMode = false
                sourceRegistry.selected.clear()
                mode = target
            }
            ThirdSourceScreenMode.BROWSE -> {
                // 搜索结果页返回搜索页，方便改关键词；普通浏览交还 MainActivity。
                searchPageState.invalidateSuggestions()
                mode = target
            }
            ThirdSourceScreenMode.HISTORY,
            ThirdSourceScreenMode.FAVORITE -> mode = target
        }
        rebuild()
        if(from == ThirdSourceScreenMode.BROWSE) scheduleSuggest()
        return true
    }

    /** 播放页按键接管；浏览态只接管网格上下翻行（RecyclerView 在固定高度容器里不会为焦点自动滚动）。 */
    fun onKey(event: KeyEvent): Boolean {
        if(event.action == KeyEvent.ACTION_DOWN && (mode == ThirdSourceScreenMode.BROWSE || mode == ThirdSourceScreenMode.HISTORY || mode == ThirdSourceScreenMode.FAVORITE) && browseGridKey(event.keyCode)) return true
        // 历史/收藏页：菜单键删除当前聚焦的记录（遥控器长按行为不一致，这里用显式按键）
        if(event.action == KeyEvent.ACTION_DOWN && mode == ThirdSourceScreenMode.HISTORY && event.keyCode == KeyEvent.KEYCODE_MENU) { deleteFocusedHistory(); return true }
        if(event.action == KeyEvent.ACTION_DOWN && mode == ThirdSourceScreenMode.FAVORITE && event.keyCode == KeyEvent.KEYCODE_MENU) { deleteFocusedFavorite(); return true }
        if(mode != ThirdSourceScreenMode.PLAY || player == null) return false
        val seekBarFocused = activity.currentFocus?.tag == "seekbar"
        val focusInOverlay = overlayButtons.any { it.hasFocus() } || seekBarFocused
        return playbackKeyController.handle(
            action = event.action,
            keyCode = event.keyCode,
            repeatCount = event.repeatCount,
            overlayVisible = overlayVisible,
            scrubActive = scrubState.active,
            seekBarFocused = seekBarFocused,
            focusInOverlay = focusInOverlay,
            onStartScrub = ::startScrub,
            onEndScrub = ::endScrub,
            onTogglePlay = ::togglePlay,
            onShowOverlay = ::showOverlay,
            onJumpEpisode = ::jumpEpisode,
            onStop = {
                saveProgressNow()
                mode = ThirdSourceScreenMode.DETAIL
                releasePlayback()
                rebuild()
            },
        )
    }

    fun pause() { player?.pause(); saveProgressNow(); keepScreenOn(false) }

    fun stop() {
        saveProgressNow()
        if(player != null) {
            playbackState.lastPositionMs = player?.currentPosition ?: -1L
            releasePlayback(); mode = ThirdSourceScreenMode.PLAY
        }
    }

    fun release() {
        saveProgressNow()
        // 先使所有在途请求的回调失效，再清理页面专属异步资源。
        generation++
        main.removeCallbacksAndMessages(null)
        releasePlayback()
        artwork.close()
        tvTools.destroy()
        client.shutdown()
        executor.shutdownNow()
        probeExecutor.shutdownNow()
        searchExecutor.shutdownNow()
    }

    // ================= 浏览态 =================

    private fun renderBrowse(container: LinearLayout) {
        detachPlayerViews()
        if(sources.isEmpty()) { renderEmptySources(container); return }
        val body = ui.column().apply { setPadding(0, ui.dp(4), 0, 0) }

        // 源胶囊条 + 管理（搜索态隐藏，把高度让给结果网格）
        var capsuleViews: List<View> = emptyList()
        var manage: TextView? = null
        var historyRef: TextView? = null
        var favoriteRef: TextView? = null
        if (searchWord == null) {
            val sourceBar = browseSourceBarRenderer.render(
                sources = sources,
                createCapsule = ::sourceCapsule,
                clearCapsuleIndicators = { capsuleDots.clear() },
                probeSummaryText = ::probeSummaryText,
                onHistory = ::openHistory,
                onFavorites = ::openFavorites,
                onManage = ::openSources,
            )
            capsuleViews = sourceBar.capsules
            sourceButtons = capsuleViews
            historyRef = sourceBar.history
            favoriteRef = sourceBar.favorite
            manage = sourceBar.manage
            body.addView(sourceBar.row)
            probeSummary = sourceBar.probeSummary
            body.addView(sourceBar.probeSummary)
        } else sourceButtons = emptyList()

        val searchBar = browseSearchBarRenderer.render(
            categories = categories,
            categoryId = catalogPageState.categoryId,
            searchWord = searchWord,
            searchDetails = browseSearchState.details,
            filterType = browseSearchState.type,
            filterYear = browseSearchState.year,
            filterArea = browseSearchState.area,
            searchSummary = ::searchSummaryText,
            onPickCategory = ::showCategoryPicker,
            onOpenSearch = { clear -> openSearch(clear = clear) },
            onScopeToggle = {
                store.searchAllSources = !store.searchAllSources
                browseSearchState.allSourcesOverride = false
                browseSearchState.details = emptyList(); browseSearchState.hits = emptyList(); browseSearchState.hitByVideoId.clear()
                searchPageState.loading = false; searchPageState.requested = false; browseSearchState.aggregating = false; loadError = null
                rebuild()
            },
            onPickFilter = { label, value ->
                when (label) {
                    "类型" -> browseSearchState.type = value
                    "年份" -> browseSearchState.year = value
                    "地区" -> browseSearchState.area = value
                }
                rebuild()
            },
            onClearFilters = {
                browseSearchState.clearFilters()
                rebuild()
            },
            showFilterPicker = ::showFilterPicker,
        )
        searchBar.rows.forEach { body.addView(it, ui.lp(-1, -2)) }
        val categoryButtons = searchBar.categoryButtons
        val filterButtons = searchBar.filterButtons
        val clearRef = searchBar.clearRef
        searchStatusView = searchBar.searchStatus
        val search = searchBar.search

        // 结果区：请求时序仍由页面控制器负责，具体空态/状态视图交给渲染器。
        if(searchWord == null && loadError != null && videos.isEmpty()) {
            browseResultRenderer.addLoadError(
                container = body,
                sourceName = currentSource()?.name.orEmpty(),
                error = loadError.orEmpty(),
                onRetry = { loadError = null; catalogPageState.requested = false; rebuild() },
            )
            container.addView(body, ui.lp(-1, 0).apply { weight = 1f })
            topFocusId = body.getChildAt(body.childCount - 1).id
            return
        }
        val display: List<ThirdVideo> = currentDisplay()
        // 自动补拉放在空态文案之前：请求会同步置 loading，文案能显示「正在加载…」而不是误报「暂无内容」
        if(videos.isEmpty() && searchWord == null && loadError == null && !loading && !catalogPageState.requested) requestCatalog(reset = true)
        // 搜索同理：失败后不自动重发（searchPageState.requested 守卫），避免「请求→失败→重建→再请求」死循环
        if(searchWord != null && browseSearchState.details.isEmpty() && loadError == null && !searchPageState.loading && !searchPageState.requested) requestSearch(reset = true)
        if(display.isEmpty()) {
            browseResultRenderer.addEmptyState(
                body,
                ThirdSourceBrowseEmptyState.message(
                    searchWord = searchWord,
                    searchDetailsCount = browseSearchState.details.size,
                    aggregating = browseSearchState.aggregating,
                    sourcesDone = browseSearchState.sourcesDone,
                    sourceTotal = browseSearchState.sourceTotal,
                    searchLoading = searchPageState.loading,
                    loadError = loadError,
                    loading = loading,
                    sourceName = currentSource()?.name,
                ),
            )
        }
        if(display.isNotEmpty()) mountGrid(body)
        gridStatus = browseResultRenderer.createGridStatus(gridStatusText())
        body.addView(gridStatus)

        container.addView(body, ui.lp(-1, 0).apply { weight = 1f })
        topFocusId = browseFocusController.connect(
            display = display,
            browseFocusKey = browseFocusKey,
            loading = loading,
            searchWord = searchWord,
            loadError = loadError,
            categoryId = catalogPageState.categoryId,
            sources = sources,
            currentId = currentId,
            capsuleViews = capsuleViews,
            categoryButtons = categoryButtons,
            filterButtons = filterButtons,
            search = search,
            clearRef = clearRef,
            historyRef = historyRef,
            favoriteRef = favoriteRef,
            manage = manage,
            gridRecycler = gridRecycler,
        )
    }

    private fun sourceCapsule(source: ThirdSource, index: Int): LinearLayout {
        val isCurrent = source.id == currentId
        val capsule = ui.row().apply { setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9)) }
        val dot = View(activity).apply {
            background = ui.rounded(dotColor(source.id), radius = 3)
            layoutParams = LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)).apply { rightMargin = ui.dp(6) }
        }
        capsuleDots[source.id] = dot
        capsule.addView(dot)
        capsule.addView(ui.text(source.name, 14f, if(isCurrent) TvStyle.accent else TvStyle.text))
        ui.focusStyle(capsule, selected = isCurrent)
        capsule.setOnClickListener { switchSource(source) }
        return capsule
    }

    private fun renderEmptySources(container: LinearLayout) {
        val body = ui.column().apply { setPadding(0, ui.dp(24), 0, 0) }
        ui.message(body, "暂无片源信息，请添加源或导入配置")
        body.addView(ui.text("若重装后没有自动恢复：可能是备份文件不存在或未授予存储权限，可在「设置 → 本地备份与恢复」中查看。", 13f, muted)
            .apply { setPadding(0, ui.dp(2), 0, ui.dp(10)) })
        // 空态同样给全入口：添加 / 导入配置 / 扫码导入导出
        val actions = ui.row().apply { setPadding(0, ui.dp(4), 0, 0) }
        val add = ui.addButton(actions, "＋ 添加源") { showAddSourceDialog() }
        val importTvbox = ui.addButton(actions, "＋ 导入配置") { showImportDialog() }
        val transfer = ui.addButton(actions, "导入/导出配置（扫码）") { showSourceTransfer() }
        add.nextFocusRightId = importTvbox.id
        importTvbox.nextFocusLeftId = add.id; importTvbox.nextFocusRightId = transfer.id
        transfer.nextFocusLeftId = importTvbox.id
        body.addView(actions)
        body.addView(ui.text(SOURCE_DISCLAIMER, 12f, muted).apply { setPadding(0, ui.dp(20), 0, 0) })
        container.addView(body, ui.lp(-1, 0).apply { weight = 1f })
        topFocusId = add.id; add.requestFocus()
    }

    private fun mountGrid(body: LinearLayout, onOpen: ((ThirdVideo) -> Unit)? = null) {
        val mounted = gridRenderer.render(
            source = ::currentDisplay,
            isBrowseMode = { mode == ThirdSourceScreenMode.BROWSE },
            onBrowseFocus = { browseFocusKey = it },
            onNearEnd = ::maybeLoadMore,
            onOpen = { video -> if(onOpen != null) onOpen(video) else onVideoClick(video) },
        )
        gridAdapter = mounted.adapter
        gridRecycler = mounted.recycler
        body.addView(mounted.recycler, ui.lp(-1, 0).apply { weight = 1f })
        sourceButtons.lastOrNull()?.nextFocusDownId = mounted.recycler.id
    }

    /** 当前结果集：浏览态用列表缓存，搜索态用筛选后的聚合结果。adapter 每次取最新，追加不必重建控件。 */
    private fun currentDisplay(): List<ThirdVideo> = when {
        mode == ThirdSourceScreenMode.HISTORY -> libraryState.historyVideos
        mode == ThirdSourceScreenMode.FAVORITE -> libraryState.favoriteVideos
        searchWord != null -> ThirdSourceSearchFilters.displayHits(filteredSearchHits(), browseSearchState.aggregate)
        else -> videos
    }

    private fun compositeVideoId(source: ThirdSource, videoId: String) =
        ThirdSourceSearchFilters.compositeVideoId(source, videoId)

    /** 应用筛选后的聚合命中（筛选条件与详情列表保持一致）。 */
    private fun filteredSearchHits(): List<ThirdSearchHit> =
        ThirdSourceSearchFilters.filterHits(browseSearchState.hits, browseSearchState.type, browseSearchState.year, browseSearchState.area)


    private fun onVideoClick(video: ThirdVideo) {
        detailSessionState.returnMode = ThirdSourceScreenMode.BROWSE
        if(searchWord != null) {
            browseSearchState.hitByVideoId[video.id]?.let { openDetail(it.detail, it.source); return }
        }
        val source = currentSource() ?: return
        val gen = generation
        detailRequests.request(source, video.id, isCurrent = { gen == generation }) { result ->
            if(result == null) Toast.makeText(activity, "获取详情失败，请重试", Toast.LENGTH_SHORT).show()
            else openDetail(result)
        }
    }

    // ================= 观看历史 =================

    private fun openHistory() { mode = ThirdSourceScreenMode.HISTORY; rebuild() }

    private fun renderHistory(container: LinearLayout) {
        detachPlayerViews()
        val aliveApis = sources.mapTo(HashSet()) { it.api }
        libraryState.loadHistory(store.history(), aliveApis, ui::formatTime)
        ThirdSourceSavedListRenderer(
            ui = ui,
            muted = muted,
            mountGrid = { body, click -> mountGrid(body, click) },
            setTopFocusId = { topFocusId = it },
            gridId = { gridRecycler?.id },
            onBack = { onBack(); Unit },
        ).render(
            container = container,
            title = "观看历史  ·  ${libraryState.historyEntries.size} 条",
            emptyTitle = "暂无观看记录，去片源里挑一部吧",
            emptyDescription = "看过的影片会出现在这里，点开即可续播。",
            hint = "按菜单键删除当前记录 · 源被移除可重新添加，影片失效可按片名重搜",
            videos = libraryState.historyVideos,
            clearLabel = if(libraryState.historyEntries.isNotEmpty()) "清空历史" else null,
            onClear = { confirmClearHistory() },
            onOpen = { video -> libraryState.historyEntry(video.id)?.let { openHistoryEntry(it) } },
        )
    }

    /** 历史卡片点击：源被删可重新添加；影片失效可在本源按片名重搜。 */
    private fun openHistoryEntry(entry: ThirdHistoryEntry) {
        val resolution = ThirdSourceSavedEntryPlanner.resolve(entry.sourceApi, sources, currentId)
        val source = resolution.source
        if(source == null) {
            tvTools.choose("源已移除", listOf("重新添加此源", "删除这条记录"), null, { choice ->
                if(choice == 0) readdHistorySource(entry) else deleteHistory(entry)
            })
            return
        }
        if(resolution.shouldSwitch) {
            currentId = source.id; store.setCurrent(source.id)
            resetBrowseState()
        }
        // 已看完的记录不再从片尾续播
        if(entry.completed) store.clearProgress(source.id, entry.videoId)
        val gen = generation
        detailRequests.request(source, entry.videoId, isCurrent = { gen == generation }) { result ->
            if(result != null) {
                detailSessionState.returnMode = ThirdSourceScreenMode.HISTORY
                openDetail(result, source)
            } else showHistoryMissing(entry, source)
        }
    }

    private fun showHistoryMissing(entry: ThirdHistoryEntry, source: ThirdSource) {
        val dead = probes[source.id]?.dot == ThirdSourceProbeDot.DEAD
        val title = if(dead) "源暂时连不上" else "影片可能已下架"
        val message = if(dead) "「${source.name}」当前无法连接，可在全部片源中按片名重新搜索。"
            else "在「${source.name}」找不到这部影片，可能已下架或更换了编号；可在全部片源中按片名重新搜索。"
        tvTools.choose("$title\n$message", listOf("在全部片源搜索", "删除这条记录"), null, { choice ->
            if(choice == 0) searchHistoryTitle(entry) else deleteHistory(entry)
        })
    }

    /** 历史失效兜底：用全部源重新聚合搜索，避免用户还要手动画源。 */
    private fun searchHistoryTitle(entry: ThirdHistoryEntry) {
        val title = entry.title.trim()
        if(title.isEmpty() || currentSource() == null) return
        submitSearch(title, allSources = true)
    }

    private fun readdHistorySource(entry: ThirdHistoryEntry) = readdSource(entry.sourceApi, entry.sourceName)

    /** 历史/收藏引用的源被删除后，按原接口重新加回源列表。 */
    private fun readdSource(api: String, name: String) {
        if(sources.size >= ThirdCms.MAX_SOURCES) {
            Toast.makeText(activity, "源数量已达上限（${ThirdCms.MAX_SOURCES}）", Toast.LENGTH_SHORT).show(); return
        }
        if(sources.any { it.api == api }) { rebuild(); return }
        val source = ThirdSource("u" + api.hashCode().toUInt().toString(), name.ifBlank { ui.hostOf(api) }, api)
        store.saveSources(sources + source)
        backupRequest()
        Toast.makeText(activity, "已重新添加「${source.name}」", Toast.LENGTH_SHORT).show()
        rebuild()
    }

    private fun deleteHistory(entry: ThirdHistoryEntry) {
        val sourceId = sources.firstOrNull { it.api == entry.sourceApi }?.id
        store.removeHistory(entry.sourceApi, entry.videoId)
        if (sourceId != null) store.clearProgress(sourceId, entry.videoId)
        backupRequest()
        Toast.makeText(activity, "已删除这条记录", Toast.LENGTH_SHORT).show()
        rebuild()
    }

    private fun deleteFocusedHistory() {
        val recycler = gridRecycler ?: return
        val focused = recycler.findFocus() ?: return
        val position = recycler.getChildAdapterPosition(focused)
        val entry = libraryState.historyEntries.getOrNull(position) ?: return
        tvTools.confirm("删除观看记录", "从观看历史中移除《${entry.title}》？播放进度也会一起清除。", focused) {
            deleteHistory(entry)
        }
    }

    private fun confirmClearHistory() {
        tvTools.confirm("清空观看历史", "将删除全部 ${libraryState.historyEntries.size} 条观看记录，不影响片源配置。", null) {
            store.clearHistory()
            backupRequest()
            Toast.makeText(activity, "观看历史已清空", Toast.LENGTH_SHORT).show()
            rebuild()
        }
    }

    // ================= 片源内收藏 =================

    private fun openFavorites() { mode = ThirdSourceScreenMode.FAVORITE; rebuild() }

    private fun renderFavorites(container: LinearLayout) {
        detachPlayerViews()
        val aliveApis = sources.mapTo(HashSet()) { it.api }
        libraryState.loadFavorites(store.favorites(), aliveApis, ui::hostOf)
        ThirdSourceSavedListRenderer(
            ui = ui,
            muted = muted,
            mountGrid = { body, click -> mountGrid(body, click) },
            setTopFocusId = { topFocusId = it },
            gridId = { gridRecycler?.id },
            onBack = { onBack(); Unit },
        ).render(
            container = container,
            title = "我的收藏  ·  ${libraryState.favoriteEntries.size} 条",
            emptyTitle = "还没有收藏，去片源详情页点「收藏」吧",
            emptyDescription = "收藏只记录影片与来源片源，随主备份一起迁移。",
            hint = "按菜单键取消收藏 · 源被移除可重新添加，影片失效可按片名重搜",
            videos = libraryState.favoriteVideos,
            clearLabel = null,
            onClear = null,
            onOpen = { video -> libraryState.favoriteEntry(video.id)?.let { openFavoriteEntry(it) } },
        )
    }

    /** 收藏卡片点击：源被删可重新添加，影片失效可按片名在全部源重搜。 */
    private fun openFavoriteEntry(entry: ThirdFavorite) {
        val resolution = ThirdSourceSavedEntryPlanner.resolve(entry.sourceApi, sources, currentId)
        val source = resolution.source
        if(source == null) {
            tvTools.choose("源已移除", listOf("重新添加此源", "取消收藏"), null, { choice ->
                if(choice == 0) readdSource(entry.sourceApi, entry.sourceName) else deleteFavorite(entry)
            })
            return
        }
        val gen = generation
        detailRequests.request(source, entry.videoId, isCurrent = { gen == generation }) { result ->
            if(result != null) {
                detailSessionState.returnMode = ThirdSourceScreenMode.FAVORITE
                openDetail(result, source)
            } else tvTools.choose("影片可能已下架", listOf("在全部片源搜索", "取消收藏"), null, { choice ->
                if(choice == 0) submitSearch(entry.title, allSources = true) else deleteFavorite(entry)
            })
        }
    }

    private fun deleteFavorite(entry: ThirdFavorite) {
        store.removeFavorite(entry.sourceApi, entry.videoId)
        backupRequest()
        Toast.makeText(activity, "已取消收藏", Toast.LENGTH_SHORT).show()
        if(mode == ThirdSourceScreenMode.FAVORITE) rebuild() else renderDetailRefresh()
    }

    private fun deleteFocusedFavorite() {
        val recycler = gridRecycler ?: return
        val focused = recycler.findFocus() ?: return
        val position = recycler.getChildAdapterPosition(focused)
        val entry = libraryState.favoriteEntries.getOrNull(position) ?: return
        tvTools.confirm("取消收藏", "将《${entry.title}》从收藏中移除？", focused) { deleteFavorite(entry) }
    }

    private fun maybeLoadMore() {
        if(mode == ThirdSourceScreenMode.HISTORY || mode == ThirdSourceScreenMode.FAVORITE) return
        if(loadError != null || currentSource() == null) return
        if(searchWord != null) { requestSearch(reset = false); return }
        if(loadingMore || !hasMore) return
        requestCatalog(reset = false)
    }

    /** 网格上下翻行：目标行不在视口内时先滚动再聚焦；已到底则拉起下一页。 */
    private fun browseGridKey(key: Int): Boolean {
        val recycler = gridRecycler ?: return false
        // 有弹窗时焦点属于弹窗窗口，不能被网格按键逻辑吞掉
        val current = activity.currentFocus ?: return false
        if(current.windowToken !== recycler.windowToken) return false
        val focused = recycler.findFocus() ?: return false
        val grid = recycler.layoutManager as? GridLayoutManager ?: return false
        val position = recycler.getChildAdapterPosition(focused)
        if(position == RecyclerView.NO_POSITION) return false
        val span = grid.spanCount.coerceAtLeast(1)
        when(key) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val next = position + span
                if(next >= (gridAdapter?.itemCount ?: 0)) { maybeLoadMore(); return true }
                // 目标行贴到视口底部：视口只有一行时即 0（整行替换），两行时等效下滚一行
                scrollAndFocus(recycler, grid, next, (recycler.height - focused.height).coerceAtLeast(0))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val prev = position - span
                if(prev < 0) return false
                scrollAndFocus(recycler, grid, prev, 0)
                return true
            }
        }
        return false
    }

    private fun scrollAndFocus(recycler: RecyclerView, grid: GridLayoutManager, position: Int, offset: Int) {
        grid.scrollToPositionWithOffset(position, offset)
        // postOnAnimation 在下一帧布局之后执行；普通 post 可能早于这次重新布局，导致目标卡片还没创建
        recycler.postOnAnimation {
            val target = grid.findViewByPosition(position)
            if(target != null) target.requestFocus()
            else recycler.postOnAnimation { grid.findViewByPosition(position)?.requestFocus() }
        }
    }

    private fun requestCatalog(reset: Boolean) {
        val source = currentSource() ?: return
        if(loading || loadingMore) return
        val gen = generation
        val requestCategory = catalogPageState.categoryId
        val requestPage = if(reset) 1 else page + 1
        if(reset) {
            // 新 reset 请求发起前取消所有在途 catalog 请求：旧请求已无人消费（generation 会丢弃其回调），
            // 不取消会占满线程池让新请求排队，界面长时间空态（体感卡死）
            client.cancelCatalog()
            loading = true
            catalogPageState.requested = true
        } else loadingMore = true

        catalogRequests.request(
            api = source.api,
            categoryId = requestCategory,
            page = requestPage,
            includeCategories = reset && !catalogPageState.categoriesCached && requestCategory == null,
            isCurrent = { gen == generation }
        ) { response ->
            val fetched = response.categories
            val result = response.page
            if(fetched != null && fetched.isNotEmpty()) { categories = fetched; catalogPageState.categoriesCached = true }
            if(reset) {
                loading = false
                if(result != null) {
                    // 分类兜底：仅首页响应含分类时更新缓存，后续页沿用
                    if(!catalogPageState.categoriesCached && result.categories.isNotEmpty()) {
                        categories = result.categories; catalogPageState.categoriesCached = true
                    }
                    val update = ThirdSourceCatalogPageReducer.replace(result)
                    videos = update.videos.toMutableList()
                    hasMore = update.hasMore; loadError = null; page = update.page
                } else { loadError = "连接超时或接口无响应"; videos = mutableListOf(); hasMore = false }
                rebuild()
            } else {
                loadingMore = false
                if(result != null) {
                    val update = ThirdSourceCatalogPageReducer.append(videos, result, requestPage)
                    videos = update.videos.toMutableList()
                    hasMore = update.hasMore; page = update.page
                    gridAdapter?.submit(update.insertAt)
                    gridStatus?.text = gridStatusText()
                } else gridStatus?.text = "加载更多失败，继续下滑可重试"
            }
        }
    }

    /** 搜索请求（ac=detail&wd=）：reset 并发聚合各源第 1 页；「仅当前源」时支持向下翻页。 */
    private fun requestSearch(reset: Boolean) {
        val current = currentSource() ?: return
        val word = searchWord ?: return
        if(!reset) { requestSearchMore(current, word); return }
        if(searchPageState.loading) return
        // 新搜索先取消上一轮在途请求，避免旧源结果串入新关键字
        client.cancelSearch()
        val seq = browseSearchState.nextSequence()
        searchPageState.loading = true; searchPageState.requested = true; searchPageState.loadingMore = false
        browseSearchState.clearResults()
        searchPageState.hasMore = false; searchPageState.total = 0; searchPageState.page = 1
        loadError = null
        val aggregate = browseSearchState.allSourcesOverride || store.searchAllSources
        val candidates = ThirdSourceSearchPlanner.candidates(
            current = current,
            sources = sources,
            aggregate = aggregate,
            probes = probes,
            limit = AGGREGATE_LIMIT,
        )
        browseSearchState.aggregate = candidates.size > 1
        browseSearchState.sourceTotal = candidates.size
        browseSearchState.aggregating = browseSearchState.aggregate && candidates.size > 1
        var firstArrival = true
        candidates.forEach { source ->
            // 不用 generation：首个结果到达会 rebuild 一次，generation 随之变化，
            // 因此聚合搜索使用序号 + 关键词守卫，避免丢弃同一轮其他源的结果。
            searchRequests.request(source, word, 1, isCurrent = { seq == browseSearchState.sequence && searchWord == word }) { _, result ->
                    browseSearchState.sourcesDone++
                    val pageData = result.page
                    if(pageData == null) browseSearchState.failures[source.api] = result.error ?: "连接失败"
                    else {
                        val merged = ThirdSourceSearchResultReducer.mergeAggregate(
                            existing = browseSearchState.hits,
                            source = source,
                            incoming = pageData.details,
                            preferredApi = current.api,
                        )
                        browseSearchState.hits = merged.hits
                        browseSearchState.details = merged.details
                        browseSearchState.hitByVideoId.clear()
                        browseSearchState.hitByVideoId.putAll(merged.hitByVideoId)
                        if(candidates.size == 1) {
                            searchPageState.hasMore = pageData.hasMore; searchPageState.total = pageData.total; searchPageState.page = 1
                        }
                    }
                    val finished = browseSearchState.sourcesDone >= candidates.size
                    searchPageState.loading = !finished
                    if(finished) {
                        browseSearchState.aggregating = false
                        if(browseSearchState.hits.isEmpty()) loadError = browseSearchState.failures.values.firstOrNull()?.let { "所有片源均未返回结果（$it）" }
                            ?: "连接超时或接口无响应"
                    }
                    // 首个结果到达才建网格；后续结果只刷新 adapter，避免整页重建抢走焦点
                    if(firstArrival || (gridAdapter == null && browseSearchState.details.isNotEmpty())) { firstArrival = false; rebuild() }
                    else {
                        searchStatusView?.text = searchSummaryText()
                        gridAdapter?.submit(-1)
                        gridStatus?.text = gridStatusText()
                    }
                }
            }
    }

    /** 「仅当前源」分页追加。 */
    private fun requestSearchMore(source: ThirdSource, word: String) {
        if(searchPageState.loadingMore || !searchPageState.hasMore) return
        searchPageState.loadingMore = true
        val seq = browseSearchState.sequence
        val requestPage = searchPageState.page + 1
        searchRequests.request(source, word, requestPage, isCurrent = { seq == browseSearchState.sequence && searchWord == word }) { _, result ->
                searchPageState.loadingMore = false
                val pageData = result.page
                if(pageData != null) {
                    val insertAt = browseSearchState.details.size
                    val merged = ThirdSourceSearchResultReducer.appendPage(
                        existing = browseSearchState.hits,
                        source = source,
                        incoming = pageData.details,
                    )
                    browseSearchState.hits = merged.hits
                    browseSearchState.details = merged.details
                    browseSearchState.hitByVideoId.clear()
                    browseSearchState.hitByVideoId.putAll(merged.hitByVideoId)
                    searchPageState.hasMore = pageData.hasMore; searchPageState.total = pageData.total; searchPageState.page = requestPage
                    gridAdapter?.submit(insertAt)
                    searchStatusView?.text = searchSummaryText()
                    gridStatus?.text = gridStatusText()
                } else gridStatus?.text = "加载更多失败，继续下滑可重试"
        }
    }

    /** 搜索结果顶栏文案：聚合模式展示已返回源数与失败源数。 */
    private fun searchSummaryText(): String = ThirdSourceStatusText.searchSummary(
        word = searchWord.orEmpty(),
        filteredCount = filteredSearchDetails().size,
        detailsCount = browseSearchState.details.size,
        loading = searchPageState.loading,
        aggregate = browseSearchState.aggregate,
        sourcesDone = browseSearchState.sourcesDone,
        sourceTotal = browseSearchState.sourceTotal,
        failureCount = browseSearchState.failures.size,
        loadFailed = loadError != null,
        total = searchPageState.total
    )

    private fun gridStatusText(): String = ThirdSourceStatusText.gridStatus(
        searchActive = searchWord != null,
        aggregating = browseSearchState.aggregating,
        sourcesDone = browseSearchState.sourcesDone,
        sourceTotal = browseSearchState.sourceTotal,
        loadingMoreSearch = searchPageState.loadingMore,
        searchDetailsCount = browseSearchState.details.size,
        aggregate = browseSearchState.aggregate,
        failureCount = browseSearchState.failures.size,
        searchHasMore = searchPageState.hasMore,
        searchTotal = searchPageState.total,
        loadingMoreCatalog = loadingMore,
        catalogHasMore = hasMore,
        catalogVideosCount = videos.size
    )

    /** 应用类型/年份/地区筛选后的搜索结果。 */
    private fun filteredSearchDetails(): List<ThirdDetail> =
        ThirdSourceSearchFilters.filterDetails(browseSearchState.details, browseSearchState.type, browseSearchState.year, browseSearchState.area)

    /** 筛选选项弹窗：第一项为「全部」。 */
    private fun showFilterPicker(title: String, options: List<String?>, current: String?, onPick: (String?) -> Unit) {
        val labels = options.map { it ?: "全部" }
        TvDialog.choose(activity, title, labels, { which -> onPick(options[which]) }).show()
    }

    private val categoryPicker by lazy { ThirdSourceCategoryPicker(activity, ui) }
    private val browseResultRenderer by lazy { ThirdSourceBrowseResultRenderer(ui, muted) }
    private val browseFocusController = ThirdSourceBrowseFocusController()
    private val searchFilterRenderer by lazy { ThirdSourceSearchFilterRenderer(ui, store) }
    private val browseSourceBarRenderer by lazy { ThirdSourceBrowseSourceBarRenderer(activity, ui) }
    private val browseSearchBarRenderer by lazy { ThirdSourceBrowseSearchBarRenderer(activity, ui, searchFilterRenderer) }

    private fun showCategoryPicker() = categoryPicker.show(categories, catalogPageState.categoryId, ::pickCategory)

    private fun pickCategory(id: String?) {
        if(id == catalogPageState.categoryId) return
        catalogPageState.categoryId = id; videos.clear(); loadError = null; page = 1; hasMore = false
        catalogPageState.requested = false; rebuild()
    }

    // ---- 源切换与探活 ----

    private fun currentSource(): ThirdSource? = sourceRegistry.currentSource()

    private fun switchSource(source: ThirdSource) {
        if(source.id == currentId) return
        currentId = source.id; store.setCurrent(source.id)
        resetBrowseState()
        scheduleProbes(); rebuild()
    }

    /** 统一清理浏览与搜索残留：换源、删源、从历史/收藏跨源打开时复用。 */
    private fun resetBrowseState() {
        catalogPageState.resetSelection(); searchWord = null
        browseSearchState.reset()
        browseSearchState.clearFilters()
        catalogPageState.resetContent()
        loadError = null
        resetSearchState()
    }

    /** 换源/删源后清空搜索页与分页状态，避免旧源结果残留。 */
    private fun resetSearchState() {
        main.removeCallbacks(suggestDebounce)
        searchPageState.input = ""; searchPageState.invalidateSuggestions()
        searchPageState.requested = false; searchPageState.loading = false; searchPageState.loadingMore = false; browseSearchState.aggregating = false
        searchPageState.page = 1; searchPageState.hasMore = false; searchPageState.total = 0
    }

    private fun scheduleProbes() {
        val cached = store.probes()
        val now = System.currentTimeMillis()
        sources.forEach { source ->
            // 30 分钟内的探活结果直接复用，冷启动不再全量请求
            cached[source.api]?.takeIf { now - it.at in 0..PROBE_TTL_MS }?.let { record ->
                probes[source.id] = if(record.ok) ThirdSourceProbeState(ThirdSourceProbeDot.OK, record.total)
                    else ThirdSourceProbeState(ThirdSourceProbeDot.DEAD, 0, record.reason)
            }
            if(probes[source.id] == null) {
                probes[source.id] = ThirdSourceProbeState(ThirdSourceProbeDot.RUNNING, 0)
                val gen = generation
                probeRequests.request(source, isCurrent = { gen == generation }) { result ->
                    probes[source.id] = if(result.ok) ThirdSourceProbeState(ThirdSourceProbeDot.OK, result.total)
                        else ThirdSourceProbeState(ThirdSourceProbeDot.DEAD, 0, result.reason)
                    capsuleDots[source.id]?.let { dot -> dot.background = ui.rounded(dotColor(source.id), radius = 3) }
                    // 源管理页里的状态文字就地刷新，避免整页重建导致焦点跳动
                    sourceStatusViews[source.id]?.let { view ->
                        val (label, color) = probeLabel(source.id)
                        view.text = label; view.setTextColor(color)
                    }
                    probeSummary?.text = probeSummaryText()
                }
            }
        }
    }

    private fun dotColor(id: String): Int = ThirdSourceStatusText.dotColor(probes[id]?.dot)

    private fun probeSummaryText(): String = ThirdSourceStatusText.probeSummary(sources, probes)

    // ================= 源管理（独立页面）/ 添加 / 导入 =================

    private val sourceStatusViews = HashMap<String, TextView>()
    private val sourceCheckViews = HashMap<String, TextView>()
    private var batchActionButton: TextView? = null
    private var batchSelectAllButton: TextView? = null

    private fun openSources() {
        sourceRegistry.batchMode = false; sourceRegistry.selected.clear()
        mode = ThirdSourceScreenMode.SOURCES
        rebuild()
    }

    /** 探活状态文案与颜色：与列表里的圆点一致。 */
    private fun probeLabel(id: String): Pair<String, Int> {
        val state = probes[id] ?: return "待探活" to muted
        return when(state.dot) {
            ThirdSourceProbeDot.OK -> "可连接 · ${ui.formatCount(state.total)}" + (latencyMs[id]?.takeIf { it >= 0 }?.let { " · ${it}ms" }.orEmpty()) to Color.rgb(76, 175, 80)
            ThirdSourceProbeDot.DEAD -> (state.reason ?: "不可用") to muted
            ThirdSourceProbeDot.RUNNING -> "探活中…" to Color.rgb(255, 180, 80)
        }
    }

    /** 依次测速（单线程串行，避免并发请求），完成后自动切到最快的可用源。 */
    private fun speedTest() {
        if(speedTesting || sources.isEmpty()) return
        speedTesting = true
        val gen = generation
        val sourceSnapshot = sources.toList()
        Toast.makeText(activity, "正在依次测速，请稍候…", Toast.LENGTH_SHORT).show()
        speedTests.run(
            sources = sourceSnapshot,
            isCurrent = { gen == generation },
            onEach = { outcome ->
                val source = outcome.source
                val result = outcome.result
                probes[source.id] = if(result.ok) ThirdSourceProbeState(ThirdSourceProbeDot.OK, result.total)
                    else ThirdSourceProbeState(ThirdSourceProbeDot.DEAD, 0, result.reason)
                latencyMs[source.id] = if(result.ok) outcome.elapsedMs else -1L
                // 只就地刷新状态文字，避免整页重建导致焦点跳动
                sourceStatusViews[source.id]?.let { view ->
                    val (label, color) = probeLabel(source.id)
                    view.text = label; view.setTextColor(color)
                }
            },
            onComplete = { summary ->
                speedTesting = false
                if(gen == generation) {
                    probeSummary?.text = probeSummaryText()
                    val winner = summary.fastest
                    if(winner == null) Toast.makeText(activity, "测速完成：没有可用片源", Toast.LENGTH_SHORT).show()
                    else {
                        Toast.makeText(activity, "测速完成：${winner.source.name} 最快（${winner.elapsedMs}ms）", Toast.LENGTH_LONG).show()
                        if(winner.source.id != currentId) switchSource(winner.source)
                    }
                }
            }
        )
    }

    private fun renderSources(container: LinearLayout) {
        detachPlayerViews()
        val renderer = ThirdSourceManagementRenderer(
            activity, ui, muted, SOURCE_DISCLAIMER, ThirdCms.MAX_SOURCES,
            capsuleDots, sourceStatusViews, sourceCheckViews
        ) { action, selectAll ->
            batchActionButton = action
            batchSelectAllButton = selectAll
        }
        topFocusId = renderer.render(
            container = container,
            sources = sources,
            currentId = currentId,
            batchMode = sourceRegistry.batchMode,
            selected = sourceRegistry.selected,
            dotColor = ::dotColor,
            probeLabel = ::probeLabel,
            onBack = { onBack(); Unit },
            onToggleBatch = {
                sourceRegistry.batchMode = !sourceRegistry.batchMode
                sourceRegistry.selected.clear()
                rebuild()
            },
            onAdd = ::showAddSourceDialog,
            onImport = ::showImportDialog,
            onTransfer = ::showSourceTransfer,
            onSpeedTest = ::speedTest,
            onToggleSelection = { id ->
                val nextSelection = ThirdSourceBatchSelection.toggle(sourceRegistry.selected, id)
                sourceRegistry.selected.clear()
                sourceRegistry.selected.addAll(nextSelection)
                refreshBatchUi()
            },
            onToggleAllSelection = {
                val nextSelection = ThirdSourceBatchSelection.toggleAll(sourceRegistry.selected, sources.map { it.id })
                sourceRegistry.selected.clear()
                sourceRegistry.selected.addAll(nextSelection)
                refreshBatchUi()
            },
            onSwitch = ::switchSource,
            onDelete = ::confirmDeleteSource,
            onBatchDelete = ::confirmBatchDelete
        )
    }

    /** 批量模式就地刷新：勾选标记、按钮文字与可用状态，不重建页面（避免焦点丢失）。 */
    private fun refreshBatchUi() {
        val count = sourceRegistry.selected.size
        sourceCheckViews.forEach { (id, check) ->
            val on = id in sourceRegistry.selected
            check.text = if(on) "✓" else "○"
            check.setTextColor(if(on) TvStyle.accent else muted)
        }
        batchActionButton?.let { button ->
            button.text = "删除所选（$count）"
            button.isEnabled = count > 0
        }
        batchSelectAllButton?.text = if(ThirdSourceBatchSelection.allSelected(sourceRegistry.selected, sources.map { it.id })) "取消全选" else "全选"
    }

    /** 单源删除：行上遥控器左键触发；删除当前源则顺延到第一个剩余源。 */
    private fun confirmDeleteSource(source: ThirdSource) {
        tvTools.confirm("删除片源", "确定删除「${source.name}」吗？删除后可通过导入配置或扫码导入找回。", null) {
            val plan = ThirdSourceRemovalPlanner.plan(
                sources = sources,
                removeIds = setOf(source.id),
                currentId = currentId,
                maxSources = ThirdCms.MAX_SOURCES,
            )
            store.saveSources(plan.remaining)
            if(plan.nextCurrentId != currentId) store.setCurrent(plan.nextCurrentId)
            plan.removedIds.forEach(probes::remove)
            resetBrowseState()
            Toast.makeText(activity, "已删除「${source.name}」", Toast.LENGTH_SHORT).show()
            backupRequest(); rebuild()
        }
    }

    /** 批量删除：选中项二次确认后移除，当前源被删则顺延到第一个剩余源。 */
    private fun confirmBatchDelete() {
        val targets = sources.filter { it.id in sourceRegistry.selected }
        if(targets.isEmpty()) return
        tvTools.confirm("批量删除源", "确定删除选中的 ${targets.size} 个源吗？删除后可通过导入配置或扫码导入找回。", null) {
            val plan = ThirdSourceRemovalPlanner.plan(
                sources = sources,
                removeIds = sourceRegistry.selected,
                currentId = currentId,
                maxSources = ThirdCms.MAX_SOURCES,
            )
            store.saveSources(plan.remaining)
            if(plan.nextCurrentId != currentId) store.setCurrent(plan.nextCurrentId)
            plan.removedIds.forEach(probes::remove)
            sourceRegistry.selected.clear(); sourceRegistry.batchMode = false
            resetBrowseState()
            Toast.makeText(activity, "已删除 ${targets.size} 个源", Toast.LENGTH_SHORT).show()
            backupRequest(); rebuild()
        }
    }

    /** 打开手机扫码页：下载当前源配置，或上传配置文件（只追加、不覆盖已有源）。 */
    private fun showSourceTransfer() {
        val config = ThirdSourceConfig.encode(store.backupSources())
        tvTools.sourceTransfer(config, null) { incoming ->
            val added = store.mergeRestored(incoming)
            probes.clear()
            if(added > 0) {
                backupRequest(); rebuild()
                Toast.makeText(activity, "已追加 $added 个源（已有源保持不变）", Toast.LENGTH_LONG).show()
            } else Toast.makeText(activity, "没有需要新增的源（均已存在或已达上限）", Toast.LENGTH_LONG).show()
        }
    }

    private fun showAddSourceDialog() = addSourceDialog.show()

    private fun showImportDialog() = importDialog.show()

    private fun showImportChecklist(rawCandidates: List<ThirdTvboxSite>, skipped: Int) {
        ThirdSourceImportChecklist(
            activity = activity,
            ui = ui,
            muted = muted,
            probeRequests = probeRequests,
            currentGeneration = { generation },
            existingApis = sources.mapTo(HashSet()) { it.api },
            onImport = ::importSources
        ).show(rawCandidates, skipped)
    }

    private fun importSources(selected: List<ThirdTvboxSite>) {
        val plan = ThirdSourceImportPlanner.plan(sources, selected, ThirdCms.MAX_SOURCES)
        if(plan.added.isNotEmpty()) store.saveSources(sources + plan.added)
        Toast.makeText(activity, plan.message, Toast.LENGTH_LONG).show()
        if(plan.importedCount > 0) backupRequest()
        rebuild()
    }

    // ================= 搜索页（独立全屏：键盘 + 联想 + 历史 + 手机扫码） =================

    private var searchPageRenderer: ThirdSourceSearchPageRenderer? = null
    private var searchPageViews: ThirdSourceSearchPageViews? = null

    /** 打开搜索页；clear=true 时同时清空上次结果（「重新搜索」入口）。 */
    private fun openSearch(clear: Boolean = false) {
        main.removeCallbacks(suggestDebounce)
        searchPageState.input = if(clear) "" else searchWord.orEmpty()
        if(clear) {
            searchWord = null
            browseSearchState.reset()
            searchPageState.resetResults()
            browseSearchState.clearFilters(); loadError = null
        }
        searchPageState.invalidateSuggestions()
        mode = ThirdSourceScreenMode.SEARCH
        rebuild()
        if(searchPageState.input.isNotBlank()) scheduleSuggest()
    }

    private fun renderSearch(container: LinearLayout) {
        detachPlayerViews()
        val renderer = ThirdSourceSearchPageRenderer(activity, ui, store, tvTools, muted)
        searchPageRenderer = renderer
        val views = renderer.render(
            container = container,
            searchInput = searchPageState.input,
            onBack = { onBack(); Unit },
            onPhoneSearch = ::phoneSearch,
            onSubmit = { submitSearch(it) },
            onTypeChar = ::typeChar,
            onDelete = {
                if(searchPageState.input.isNotEmpty()) {
                    searchPageState.input = searchPageState.input.dropLast(1)
                    refreshSearchInput()
                    scheduleSuggest()
                }
            },
            onClear = {
                searchPageState.input = ""
                refreshSearchInput()
                searchPageState.suggestions = emptyList()
                searchPageState.suggestionSequence++
                renderSuggestions()
            },
            onSearch = { submitSearch(searchPageState.input) },
            onShowAllHistory = ::showAllHistory,
            onClearHistory = { store.clearSearches(); rebuild() }
        )
        searchPageViews = views
        topFocusId = views.firstFocusId
        renderer.renderSuggestions(views, searchPageState.suggestions, searchPageState.input) { submitSearch(it) }
    }

    /** 只重建联想区：输入过程中不重建整页，避免焦点从键盘跳走。 */
    private fun renderSuggestions() {
        val renderer = searchPageRenderer ?: return
        val views = searchPageViews ?: return
        renderer.renderSuggestions(views, searchPageState.suggestions, searchPageState.input) { submitSearch(it) }
    }

    private fun typeChar(ch: String) {
        if(searchPageState.input.length >= 40) return
        searchPageState.input += ch
        refreshSearchInput(); scheduleSuggest()
    }

    private fun refreshSearchInput() {
        val view = searchPageViews?.inputView ?: return
        view.text = searchPageState.input.ifEmpty { "输入片名或关键词" }
        view.setTextColor(if(searchPageState.input.isEmpty()) muted else TvStyle.text)
    }

    private fun scheduleSuggest() {
        main.removeCallbacks(suggestDebounce)
        main.postDelayed(suggestDebounce, 300)
    }

    /** 联想请求：300ms 防抖 + 序号守卫（只接受最新输入的结果），接口不支持或失败时静默为空。 */
    private fun runSuggest() {
        val source = currentSource() ?: return
        val word = searchPageState.input.trim()
        val seq = ++searchPageState.suggestionSequence
        if(word.isEmpty()) { searchPageState.suggestions = emptyList(); renderSuggestions(); return }
        suggestionRequests.request(
            source = source,
            word = word,
            isCurrent = { seq == searchPageState.suggestionSequence && mode == ThirdSourceScreenMode.SEARCH },
        ) { list ->
            searchPageState.suggestions = list
            renderSuggestions()
        }
    }

    /** 提交搜索：切回浏览态展示结果并写入搜索历史；allSources 用于历史/收藏失效后的全源重搜。 */
    private fun submitSearch(word: String, allSources: Boolean = false) {
        val clean = word.trim()
        if(clean.isEmpty() || currentSource() == null) return
        main.removeCallbacks(suggestDebounce)
        searchWord = clean; searchPageState.input = clean
        store.rememberSearch(clean)
        browseSearchState.clearResults()
        browseSearchState.allSourcesOverride = allSources
        searchPageState.resetResults()
        browseSearchState.clearFilters(); loadError = null
        catalogPageState.categoryId = null
        mode = ThirdSourceScreenMode.BROWSE
        rebuild()
    }

    private fun phoneSearch(anchor: View?) {
        tvTools.phoneForm(anchor, PhoneForm(
            heading = "搜索片源",
            description = "输入片名或关键词，提交后电视会立即按当前片源搜索。",
            fields = listOf(PhoneField("query", "片名或关键词", "例如：爱情", 40)),
            doneHint = "请回到电视查看搜索结果。",
            invalidMessage = "请输入 1—40 个字符的关键词"
        )) { values ->
            val word = values["query"].orEmpty()
            if(word.isNotBlank()) submitSearch(word)
        }
    }

    private fun showAllHistory(history: List<String>) {
        TvDialog.choose(activity, "搜索历史（${history.size}）", history, { which -> submitSearch(history[which]) }).show()
    }


    // ================= 详情态 =================

    private fun openDetail(data: ThirdDetail, source: ThirdSource? = null) {
        detail = data
        detailSource = source ?: currentSource()
        detailSessionState.synopsisExpanded = false
        val progress = store.progress(detailSource?.id.orEmpty(), data.video.id)
        detailGroup = progress?.group?.takeIf { data.groups.getOrNull(it) != null } ?: ThirdCms.defaultGroupIndex(data.groups)
        playbackState.group = detailGroup; playbackState.episode = progress?.episode ?: 0
        playbackState.resetFallbackAttempts()
        mode = ThirdSourceScreenMode.DETAIL
        rebuild()
    }

    private fun renderDetail(container: LinearLayout) {
        val data = detail ?: run {
            mode = ThirdSourceScreenMode.BROWSE
            renderBrowse(container)
            return
        }
        val renderer = ThirdSourceDetailRenderer(activity, ui, store, artwork, muted)
        topFocusId = renderer.render(
            container = container,
            data = data,
            source = detailSource,
            sourceCount = sources.size,
            detailGroup = detailGroup,
            playGroup = playbackState.group,
            synopsisExpanded = detailSessionState.synopsisExpanded,
            onSynopsisExpanded = { detailSessionState.synopsisExpanded = it },
            onBack = { onBack(); Unit },
            onSwitchSource = ::showSourceSwitcher,
            onToggleFavorite = ::toggleDetailFavorite,
            onPlay = { group, episode, startMs -> playEpisode(group, episode, startMs) },
            onSelectGroup = { index ->
                playbackState.group = index
                playbackState.episode = 0
                renderDetailRefresh()
            }
        )
    }

    private fun renderDetailRefresh() {
        if(mode == ThirdSourceScreenMode.DETAIL) rebuild()
    }

    /** 详情页收藏/取消收藏：只记录影片与来源源，随主备份迁移。 */
    private fun toggleDetailFavorite(data: ThirdDetail) {
        val source = detailSource ?: return
        val progress = store.progress(source.id, data.video.id)
        val group = progress?.group?.takeIf { data.groups.getOrNull(it) != null } ?: ThirdCms.defaultGroupIndex(data.groups)
        val episode = progress?.episode ?: 0
        val label = data.groups.getOrNull(group)?.second?.getOrNull(episode)?.label.orEmpty()
        val added = store.toggleFavorite(ThirdFavorite(source.api, source.name, data.video.id, data.video.name, data.video.cover,
            episode, label, progress?.positionMs ?: 0, System.currentTimeMillis()))
        backupRequest()
        Toast.makeText(activity, if(added) "已收藏《${data.video.name}》" else "已取消收藏", Toast.LENGTH_SHORT).show()
        renderDetailRefresh()
    }

    // ---- 详情页换源 ----

    /** 换源弹窗：列出当前源之外的所有源，连接正常的排在前面。 */
    private fun showSourceSwitcher(data: ThirdDetail) {
        val ordered = sources.filter { it.api != detailSource?.api }
            .sortedBy { if(probes[it.id]?.dot == ThirdSourceProbeDot.DEAD) 1 else 0 }
        if(ordered.isEmpty()) { Toast.makeText(activity, "没有其它片源可切换", Toast.LENGTH_SHORT).show(); return }
        tvTools.choose("换源播放《${data.video.name}》", ordered.map { it.name }, null, { choice ->
            ordered.getOrNull(choice)?.let { target -> switchDetailSource(data, target) }
        })
    }

    /** 在目标源按片名重找同一部影片，并尽量保留当前集号；目标源已有进度时优先续播。 */
    private fun switchDetailSource(data: ThirdDetail, target: ThirdSource) {
        val gen = generation
        val episode = playbackState.episode
        Toast.makeText(activity, "正在「${target.name}」查找《${data.video.name}》…", Toast.LENGTH_SHORT).show()
        searchRequests.request(
            source = target,
            word = data.video.name,
            page = 1,
            isCurrent = { gen == generation }
        ) { _, result ->
            val matched = ThirdSourceMatchRules.bestDetail(
                result.page?.details.orEmpty(), data.video.name, data.year
            )
            if(matched == null) {
                Toast.makeText(activity, "「${target.name}」暂未找到这部影片", Toast.LENGTH_SHORT).show()
                return@request
            }
            openDetail(matched, target)
            // 目标源没有进度时沿用当前集号，越界则落在最后一集
            if(store.progress(target.id, matched.video.id) == null) {
                val size = matched.groups.getOrNull(detailGroup)?.second?.size ?: 0
                if(size > 0) { playbackState.episode = episode.coerceAtMost(size - 1); renderDetailRefresh() }
            }
            Toast.makeText(activity, "已切换到「${target.name}」", Toast.LENGTH_SHORT).show()
        }
    }


    // ================= 播放态 =================

    private fun playEpisode(group: Int, episode: Int, startMs: Long = 0L) {
        if(detail?.groups?.getOrNull(group)?.second?.getOrNull(episode) == null) return
        saveProgressNow()
        playbackState.resetFallbackAttempts()
        mode = ThirdSourceScreenMode.PLAY
        playbackState.queuePlay(group, episode, startMs)
        rebuild()
    }

    private fun attachPlayer(startMs: Long, autoplay: Boolean) {
        detachPlayerViews()
        // 换集/重建时先停掉进行中的快进预览，并释放可能残存的旧实例，避免音频串音
        scrubState.stop(); main.removeCallbacks(scrubTick); lastBandwidthBps = 0
        player?.let { stale -> stale.removeListener(playerListener); stale.release() }
        player = null
        val data = detail ?: return
        val group = data.groups.getOrNull(playbackState.group) ?: return
        val episode = group.second.getOrNull(playbackState.episode) ?: return
        // 片头跳过位置在启动前计算，交由会话工厂在 playWhenReady 前应用。
        val startMark = store.skipMark(playbackSkipController.storageKey(data.video.id))
        val introSeekMs = if(startMs <= 0L) (startMark?.intro ?: 0) * 1000L else 0L
        val session = playerSessionFactory.create(
            episodeUrl = episode.url,
            startMs = startMs,
            autoplay = autoplay,
            playbackSpeed = store.playbackSpeed,
            resizeMode = ThirdSourcePlaybackRules.resizeModeValue(store.resizeMode),
            initialSeekMs = introSeekMs
        )
        val host = session.host
        val view = session.view
        val instance = session.player
        playerView = view
        playLoading = session.loading
        playErrorText = session.error
        if(introSeekMs > 0L) Toast.makeText(activity, "已跳过片头 ${startMark?.intro ?: 0} 秒", Toast.LENGTH_SHORT).show()
        playbackSkipController.resetOutro()
        main.removeCallbacks(outroTick)
        main.postDelayed(outroTick, 1_000L)
        player = instance
        playerHost = host

        buildOverlay(host, data, group.first)
        (activity.findViewById(android.R.id.content) as FrameLayout).addView(host, FrameLayout.LayoutParams(-1, -1))
        scheduleSave()
    }

    private fun buildOverlay(host: FrameLayout, data: ThirdDetail, groupName: String) {
        val views = ThirdSourcePlayerOverlayRenderer(activity, ui).render(
            host = host,
            data = data,
            episodeLabel = data.groups.getOrNull(playbackState.group)?.second?.getOrNull(playbackState.episode)?.label.orEmpty(),
            sourceName = detailSource?.name.orEmpty(),
            groupName = groupName,
            speedLabel = PlaybackSpeed.label(store.playbackSpeed),
            resizeLabel = ThirdSourcePlaybackRules.resizeModeLabel(store.resizeMode),
            onPrevious = { jumpEpisode(-1) },
            onRewind = { seek(-10_000L) },
            onForward = { seek(10_000L) },
            onCycleSpeed = ::cycleSpeed,
            onCycleResize = ::cycleResize,
            onSkip = playbackSkipController::showDialog,
            onNext = { jumpEpisode(1) },
            onBack = { onBack(); Unit },
            onProgressFocus = ::updatePlayUi,
        )
        overlay = views.bottom
        overlayTopView = views.top
        progressFill = views.fill
        progressTrackView = views.track
        progressThumb = views.thumb
        progressBubble = views.bubble
        seekBarView = views.seekBar
        playTime = views.time
        overlayButtons.clear()
        overlayButtons.addAll(views.buttons.filterIsInstance<TextView>())
    }

    /** 倍速循环切换并记忆：0.75× → 1× → 1.25× → 1.5× → 1.75× → 2×。 */
    private fun cycleSpeed(button: TextView) {
        val options = PlaybackSpeed.options
        val index = options.indexOf(store.playbackSpeed).takeIf { it >= 0 } ?: options.indexOf(1f)
        val next = options[(index + 1) % options.size]
        store.playbackSpeed = next
        player?.setPlaybackSpeed(next)
        button.text = "倍速 ${PlaybackSpeed.label(next)}"
        Toast.makeText(activity, "播放速度 ${PlaybackSpeed.label(next)}", Toast.LENGTH_SHORT).show()
    }

    /** 画面比例循环：适应(原始比例) → 拉伸(满屏) → 裁剪(去黑边)，本机记忆。 */
    private fun cycleResize(button: TextView) {
        val next = (store.resizeMode + 1) % 3
        store.resizeMode = next
        playerView?.resizeMode = ThirdSourcePlaybackRules.resizeModeValue(next)
        button.text = "比例 ${ThirdSourcePlaybackRules.resizeModeLabel(next)}"
        Toast.makeText(activity, "画面比例：${ThirdSourcePlaybackRules.resizeModeLabel(next)}", Toast.LENGTH_SHORT).show()
    }

    /** 开始平滑快进/快退：暂停声音、只跳关键帧做画面预览，由节奏器按爬坡步长推进。 */
    private fun startScrub(direction: Int) {
        val instance = player ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if(scrubState.active) {
            scrubState.changeDirection(direction, now)
            return
        }
        scrubState.start(direction, instance.currentPosition.coerceAtLeast(0L), now)
        instance.pause()
        // 预览阶段只定位关键帧：雷鸟鹏7这类低端电视上解码开销最小，画面跟手
        instance.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        if(!overlayVisible) showOverlay(focusButtons = false)
        main.removeCallbacks(hideOverlayRun)   // 按住期间不收起控制层
        main.postDelayed(scrubTick, 180L)
    }

    /** 松开按键：回到精确定位落点，恢复播放与控制层自动收起。 */
    private fun endScrub() {
        if(!scrubState.active) return
        val targetPosition = scrubState.positionMs
        scrubState.stop()
        main.removeCallbacks(scrubTick)
        val instance = player ?: return
        instance.setSeekParameters(SeekParameters.DEFAULT)
        val duration = instance.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        instance.seekTo(targetPosition.coerceIn(0L, duration))
        // 松开即恢复播放：预览前的暂停只是实现细节，用户预期松开继续看
        instance.play()
        // 焦点放回进度条，方便继续微调或下移到按钮
        seekBarView?.requestFocus()
        main.removeCallbacks(hideOverlayRun); main.postDelayed(hideOverlayRun, 6_000L)
        updatePlayUi()
    }

    private val overlayButtons: MutableList<TextView> by playerViews::overlayButtons
    private var overlayTopView: View? by playerViews::overlayTopView
    private var progressTrackView: View? by playerViews::progressTrackView
    private var lastSeekAt: Long by playbackUiState::lastSeekAt
    private val outroTick: Runnable = object : Runnable {
        override fun run() {
            playbackSkipController.checkOutro()
            if(player != null) main.postDelayed(this, 1_000L)
        }
    }

    private fun showOverlay(focusButtons: Boolean = true) {
        overlayVisible = true
        overlayTopView?.visibility = View.VISIBLE
        overlay?.visibility = View.VISIBLE
        updatePlayUi()
        main.removeCallbacks(hideOverlayRun)
        main.postDelayed(hideOverlayRun, 10_000L)
        // 快进预览期间不抢焦点：焦点跳到按钮会中断对左右键的拦截
        if(focusButtons) {
            val focusTarget = overlayButtons.firstOrNull { it.text == "上一集" && playbackState.episode > 0 } ?: overlayButtons.firstOrNull()
            focusTarget?.requestFocus()
        }
    }

    private fun hideOverlay() {
        overlayVisible = false
        overlayTopView?.visibility = View.GONE
        overlay?.visibility = View.GONE
        main.removeCallbacks(hideOverlayRun)
    }

    private fun togglePlay() {
        val instance = player ?: return
        if(instance.isPlaying) instance.pause() else instance.play()
        if(overlayVisible) { main.removeCallbacks(hideOverlayRun); main.postDelayed(hideOverlayRun, 10_000L) }
    }

    private fun seek(deltaMs: Long) {
        val instance = player ?: return
        // 按住连发会每秒产生多次按键：限制定位频率，避免大量 seek 把播放器拖死
        val now = android.os.SystemClock.uptimeMillis()
        if(!playbackUiState.tryBeginSeek(now)) return
        val target = (instance.currentPosition + deltaMs).coerceIn(0L, (instance.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        instance.seekTo(target)
        if(!overlayVisible) showOverlay() else { main.removeCallbacks(hideOverlayRun); main.postDelayed(hideOverlayRun, 10_000L) }
    }

    private fun jumpEpisode(direction: Int) {
        val episodes = detail?.groups?.getOrNull(playbackState.group)?.second ?: return
        val next = playbackState.episode + direction
        if(next < 0) { Toast.makeText(activity, "已是第一集", Toast.LENGTH_SHORT).show(); return }
        if(next >= episodes.size) { Toast.makeText(activity, "已是最后一集", Toast.LENGTH_SHORT).show(); return }
        playEpisode(playbackState.group, next)
    }

    private fun updatePlayUi() {
        val instance = player ?: return
        val duration = instance.duration.takeIf { it > 0 } ?: 0L
        // 快进预览期间以虚拟位置为准：播放器实际位置异步追赶，直接读会来回跳
        val rawPosition = if(scrubState.active) scrubState.positionMs else instance.currentPosition
        val position = rawPosition.coerceIn(0L, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val timeText = "${ui.formatTime(position)} / ${if(duration > 0) ui.formatTime(duration) else "直播中"}"
        playTime?.text = timeText
        progressBubble?.text = timeText
        val track = progressTrackView
        val fill = progressFill
        if(track != null && track.width > 0) {
            val ratio = if(duration > 0) position.toDouble() / duration else 0.0
            if(fill != null) {
                fill.layoutParams = FrameLayout.LayoutParams((track.width * ratio).toInt().coerceAtLeast(1), -1)
                fill.requestLayout()
            }
            progressThumb?.let { thumb ->
                val size = thumb.layoutParams.width
                if(size > 0) {
                    val thumbLp = thumb.layoutParams as FrameLayout.LayoutParams
                    thumbLp.leftMargin = ((track.width * ratio).toInt() - size / 2).coerceIn(0, (track.width - size).coerceAtLeast(0))
                    thumb.layoutParams = thumbLp
                }
            }
        }
        if(overlayVisible) { main.removeCallbacks(uiTick); main.postDelayed(uiTick, 500) }
    }

    private fun saveProgressNow() {
        val instance = player ?: return
        val data = detail ?: return
        val source = detailSource ?: return
        progressRecorder.save(
            source = source,
            detail = data,
            group = playbackState.group,
            episode = playbackState.episode,
            positionMs = instance.currentPosition,
            durationMs = instance.duration.coerceAtLeast(0L)
        )
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveRun)
        main.postDelayed(saveRun, 5_000L)
    }

    private fun releasePlayback() {
        main.removeCallbacks(saveRun); main.removeCallbacks(hideOverlayRun); main.removeCallbacks(uiTick); main.removeCallbacks(outroTick)
        scrubState.stop(); main.removeCallbacks(scrubTick)
        playbackSkipController.resetOutro()
        overlayVisible = false
        playerHost?.let { host -> (host.parent as? FrameLayout)?.removeView(host) }
        playerViews.clear()
        player?.let { instance -> instance.removeListener(playerListener); instance.release() }
        player = null
        keepScreenOn(false)
    }

    private fun detachPlayerViews() {
        playerHost?.let { host -> (host.parent as? FrameLayout)?.removeView(host) }
    }


}
