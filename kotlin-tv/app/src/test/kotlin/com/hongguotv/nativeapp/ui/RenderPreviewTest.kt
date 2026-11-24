package com.hongguotv.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.hongguotv.core.BackupProgress
import com.hongguotv.core.ContentType
import com.hongguotv.core.Detail
import com.hongguotv.core.RankBoard
import com.hongguotv.core.RecentSearch
import com.hongguotv.core.Series
import com.hongguotv.core.SeriesSocial
import com.hongguotv.core.TheaterFilterGroup
import com.hongguotv.core.TheaterFilterOption
import com.hongguotv.nativeapp.ui.data.DetailState
import com.hongguotv.nativeapp.ui.data.HomeState
import com.hongguotv.nativeapp.ui.data.RankingState
import com.hongguotv.nativeapp.ui.data.SearchState
import com.hongguotv.nativeapp.ui.data.Shelf
import com.hongguotv.nativeapp.ui.data.ShelfEntry
import com.hongguotv.nativeapp.ui.data.ShelfStyle
import com.hongguotv.nativeapp.ui.data.TheaterState
import com.hongguotv.nativeapp.ui.nav.CollectionKind
import com.hongguotv.nativeapp.ui.components.TopBar
import com.hongguotv.nativeapp.ui.nav.Tab
import com.hongguotv.nativeapp.ui.data.LibraryState
import com.hongguotv.nativeapp.ui.screens.DetailScreen
import com.hongguotv.nativeapp.ui.screens.LibraryScreen
import com.hongguotv.nativeapp.ui.screens.HomeScreen
import com.hongguotv.nativeapp.ui.screens.RankingScreen
import com.hongguotv.nativeapp.ui.screens.SearchScreen
import com.hongguotv.nativeapp.ui.screens.TheaterScreen
import com.hongguotv.nativeapp.ui.components.LocalImageCrossfadeMillis
import com.hongguotv.nativeapp.ui.theme.HongguoTheme
import com.hongguotv.nativeapp.ui.theme.Ink
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-mdpi")
class RenderPreviewTest {

    @get:Rule
    val compose = createAndroidComposeRule<PreviewActivity>()

    private val all = PreviewData.series()

    @Test
    fun home() {
        val state = HomeState(
            loading = false,
            hero = all.take(5),
            shelves = listOf(
                Shelf(
                    key = "resume",
                    title = "接着看",
                    subtitle = "上次停在的地方",
                    style = ShelfStyle.RESUME,
                    collection = CollectionKind.RESUME,
                    entries = all.take(6).mapIndexed { index, item ->
                        ShelfEntry(item, "第 ${index + 3} 集 · 03:2$index", 0.35f + index * 0.07f)
                    },
                ),
                Shelf(
                    key = "hot",
                    title = "热门短剧",
                    subtitle = "今天大家都在追",
                    entries = all.drop(5).take(9).map { ShelfEntry(it, it.badge) },
                ),
            ),
        )
        render("01-home", chrome = Tab.HOME) {
            HomeScreen(
                state = state,
                favoriteIds = setOf(all[2].id),
                progressById = mapOf(
                    all[0].id to BackupProgress(all[0], "ep", 2, 30_000, 120_000, false, 0),
                ),
                onPlay = {}, onDetail = {}, onFavorite = {}, onCollection = {}, onRefresh = {}, onHeroIndex = {},
            )
        }
    }

    @Test
    fun theater() {
        val state = TheaterState(
            type = ContentType.SHORT,
            groups = listOf(
                TheaterFilterGroup("genre", "题材", listOf("都市", "悬疑", "古装", "甜宠", "逆袭", "萌宝").map { TheaterFilterOption(it, it) }),
                TheaterFilterGroup("status", "状态", listOf("完结", "连载").map { TheaterFilterOption(it, it) }),
            ),
            selection = mapOf("genre" to "全部"),
            items = all.take(18),
            hasMore = true,
            loading = false,
        )
        render("02-theater", chrome = Tab.THEATER) {
            TheaterScreen(
                state = state, onType = {}, onFilter = { _, _ -> }, onResetFilters = {}, onLoadMore = {}, onDetail = {}, onRetry = {},
            )
        }
    }

    @Test
    fun ranking() {
        val state = RankingState(
            board = RankBoard.TOTAL,
            items = all.take(8),
            heat = all.take(8).associate { it.id to "${9000 - (it.id.hashCode() % 3000)}万" },
            loading = false,
            updatedText = "每小时更新",
        )
        render("03-ranking", chrome = Tab.RANK) {
            RankingScreen(state = state, onBoard = {}, onDetail = {}, onLoadMore = {}, onRetry = {})
        }
    }

    @Test
    fun detail() {
        val series = all[0]
        val episodes = (1..24).map { "第 $it 集" }
        val state = DetailState(
            loading = false,
            series = series,
            detail = Detail(
                series = series,
                episodes = episodes,
                related = all.drop(1).take(6),
                social = SeriesSocial(9.2, "12.8万人评分", "86.4万收藏", "9096万热度"),
            ),
            resumeIndex = 2,
            resumePosition = 30_000,
            resumeProgress = 0.35f,
            favorite = true,
        )
        render("04-detail") {
            DetailScreen(
                state = state, onBack = {}, onPlay = { _, _ -> }, onNextEpisode = {}, onFavorite = {},
                onLater = {}, onSeries = {}, onExpandSynopsis = {},
            )
        }
    }

    @Test
    fun search() {
        val state = SearchState(
            query = "闪婚",
            results = all.take(9),
            hot = all.drop(2).take(6),
            history = listOf(RecentSearch("豪门", ContentType.SHORT), RecentSearch("战神", ContentType.COMIC)),
            hotLoading = false,
            searching = false,
            submitted = true,
        )
        render("05-search") {
            SearchScreen(
                state = state, onQuery = {}, onSubmit = {}, onType = {}, onKeyboard = {}, onLoadMore = {},
                onClearHistory = {}, onClear = {}, onHistory = { _, _ -> }, onDetail = {}, onBack = {},
            )
        }
    }

    @Test
    fun library() {
        val state = LibraryState(
            favorites = all.drop(3).take(8),
            history = all.take(5).mapIndexed { index, item ->
                BackupProgress(item, "ep-$index", index + 1, 30_000L + index * 60_000, 120_000, false, 0)
            },
            later = all.drop(10).take(4),
            favoriteLabels = all.drop(3).take(8).associate { it.id to "追剧中" },
            version = 3,
        )
        render("06-library", chrome = Tab.LIBRARY) {
            LibraryScreen(
                state = state, onDetail = {}, onResume = {}, onRemoveHistory = {}, onClearHistory = {},
                onToggleFavorite = {}, onToggleLater = {}, onGoTheater = {}, version = state.version,
            )
        }
    }

    @Test
    fun settings() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.hongguotv.nativeapp.ui.data.AppViewModel(app)
        val snapshot = com.hongguotv.nativeapp.ui.data.SettingsSnapshot(
            quality = 3,
            speed = 1f,
            autoNext = true,
            autoSeason = true,
            danmaku = true,
            showClock = true,
            frameMode = "FIT",
        )
        render("07-settings", chrome = Tab.SETTINGS) {
            com.hongguotv.nativeapp.ui.screens.SettingsScreen(
                vm = vm,
                snapshot = snapshot,
                versionName = "1.4.2",
            )
        }
    }

    @Test
    fun settingsDanmaku() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.hongguotv.nativeapp.ui.data.AppViewModel(app)
        val snapshot = com.hongguotv.nativeapp.ui.data.SettingsSnapshot(
            quality = 3, speed = 1f, autoNext = true, autoSeason = true, danmaku = true,
            showClock = true, frameMode = "FIT",             danmakuOpacity = 80, danmakuFont = 3, danmakuSpeed = 2, danmakuRows = 5,
            danmakuColor = 1, blockedWords = listOf("加群", "微信", "公众号"),
        )
        render("08-settings-danmaku", chrome = Tab.SETTINGS) {
            com.hongguotv.nativeapp.ui.screens.SettingsScreen(vm = vm, snapshot = snapshot, versionName = "1.4.2")
        }
        compose.onAllNodesWithText("弹幕")[0].performClick()
        compose.waitForIdle()
        capture("08-settings-danmaku")
    }

    private fun render(name: String, chrome: Tab? = null, content: @Composable () -> Unit) {
        compose.setContent {
            HongguoTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalImageCrossfadeMillis provides 0,
                ) {
                    Box(Modifier.fillMaxSize().background(Ink.Deep)) {
                        content()
                        if (chrome != null) {
                            TopBar(
                                current = chrome,
                                onSelect = {},
                                onSearch = {},
                                elevated = false,
                                clock = "21:30",
                                accountName = "红果用户",
                                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        // 给 Coil 的 IO 线程时间完成解码，并推进主线程调度让 crossfade 动画跑完
        val main = Shadows.shadowOf(Looper.getMainLooper())
        repeat(10) {
            Thread.sleep(220)
            main.idle()
            main.idleFor(java.time.Duration.ofMillis(200))
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(900)
        compose.waitForIdle()
        main.idleFor(java.time.Duration.ofMillis(600))
        compose.waitForIdle()
        capture(name)
    }

    private fun capture(name: String) {
        val width = 1280
        val height = 720
        val decor = compose.activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val file = File("/workspace/preview/shots/$name.png").apply { parentFile?.mkdirs() }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("WROTE $file ${bitmap.width}x${bitmap.height}")
    }
}

object PreviewData {
    fun series(): List<Series> {
        val file = File("/workspace/preview/series.tsv")
        if (!file.exists()) return fallback()
        val posters = File("/workspace/preview/posters").listFiles()?.sortedBy { it.name }.orEmpty()
        return file.readLines().mapIndexedNotNull { index, line ->
            val parts = line.split("\t")
            if (parts.size < 5) return@mapIndexedNotNull null
            val cover = posters.getOrNull(index % posters.size.coerceAtLeast(1))?.let { "file://${it.absolutePath}" }
                ?: parts[2]
            Series(
                parts[0],
                parts[1],
                cover,
                "一场意外的重逢，让两个原本毫无交集的人被命运重新绑在一起。她想逃，他却步步紧逼，直到所有秘密被揭开。",
                parts[3],
                parts[4],
            )
        }.ifEmpty { fallback() }
    }

    private fun fallback() = listOf(
        Series("1", "测试剧集 A", "", "一场意外的重逢", "都市 爱情", "都市"),
        Series("2", "测试剧集 B", "", "悬疑", "悬疑 推理", "悬疑"),
    )
}
