// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context

/**
 * 电视端本地播放增强设置：弹幕屏蔽词与遥控器按键。
 * 设置保存在本机。
 */
class PlaybackExtras(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 弹幕屏蔽词：命中任意一个即不展示该条弹幕。 */
    var blockedWords: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED, DEFAULT_BLOCKED)?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        set(value) { prefs.edit().putStringSet(KEY_BLOCKED, value.map { it.trim() }.filter { it.isNotEmpty() }.toSet()).apply() }

    fun blocks(text: String) = blockedWords.any { it.isNotEmpty() && text.contains(it, ignoreCase = true) }

    /** 进入应用后直接播放首页推荐位第一部。 */
    var startupAutoplay: Boolean
        get() = prefs.getBoolean(KEY_STARTUP_AUTOPLAY, false)
        set(value) { prefs.edit().putBoolean(KEY_STARTUP_AUTOPLAY, value).apply() }

    /** 无感连播：开启时第二个播放器预渲染下一集首帧并接管；关闭时同一播放器用 playlist 预载下一集。 */
    var seamlessPlayback: Boolean
        get() = prefs.getBoolean(KEY_SEAMLESS, false)
        set(value) { prefs.edit().putBoolean(KEY_SEAMLESS, value).apply() }

    /** 首页/剧场每行海报数，0 表示按屏幕宽度自动。 */
    var posterColumns: Int
        get() = prefs.getInt(KEY_POSTER_COLUMNS, 0).coerceIn(0, 8)
        set(value) { prefs.edit().putInt(KEY_POSTER_COLUMNS, value.coerceIn(0, 8)).apply() }

    /**
     * 首页大海报的取数来源（综合 / 真人剧 / 漫剧 / AI 剧）。
     * 存字符串而非序号，后续新增来源时老值不会错位。
     */
    var heroSource: String
        get() = prefs.getString(KEY_HERO_SOURCE, HeroSource.MIXED.storedValue) ?: HeroSource.MIXED.storedValue
        set(value) { prefs.edit().putString(KEY_HERO_SOURCE, HeroSource.fromStored(value).storedValue).apply() }

    /** 弹幕颜色：0 默认、1 多彩、2..9 固定色。 */
    var danmakuColorMode: Int
        get() = prefs.getInt(KEY_DANMAKU_COLOR, 0).coerceIn(0, 9)
        set(value) { prefs.edit().putInt(KEY_DANMAKU_COLOR, value.coerceIn(0, 9)).apply() }

    /** 遥控器左右短按的快进步长（秒）。 */
    var seekStepSeconds: Int
        get() = prefs.getInt(KEY_SEEK_STEP, 10).takeIf { it in SEEK_STEPS } ?: 10
        set(value) { prefs.edit().putInt(KEY_SEEK_STEP, value).apply() }

    private fun remoteAction(key: String, fallback: String) =
        prefs.getString(key, fallback)?.takeIf { it in REMOTE_ACTIONS } ?: fallback

    var remoteUpAction: String
        get() = remoteAction(KEY_REMOTE_UP, REMOTE_PREVIOUS)
        set(value) { prefs.edit().putString(KEY_REMOTE_UP, value.takeIf { it in REMOTE_ACTIONS } ?: REMOTE_NONE).apply() }

    var remoteDownAction: String
        get() = remoteAction(KEY_REMOTE_DOWN, REMOTE_NEXT)
        set(value) { prefs.edit().putString(KEY_REMOTE_DOWN, value.takeIf { it in REMOTE_ACTIONS } ?: REMOTE_NONE).apply() }

    var remoteConfirmAction: String
        get() = remoteAction(KEY_REMOTE_CONFIRM, REMOTE_PLAY_PAUSE)
        set(value) { prefs.edit().putString(KEY_REMOTE_CONFIRM, value.takeIf { it in REMOTE_ACTIONS } ?: REMOTE_NONE).apply() }

    var remoteMenuAction: String
        get() = remoteAction(KEY_REMOTE_MENU, REMOTE_MENU)
        set(value) { prefs.edit().putString(KEY_REMOTE_MENU, value.takeIf { it in REMOTE_ACTIONS } ?: REMOTE_NONE).apply() }

    companion object {
        private const val PREFS = "playback-extras-v1"
        private const val KEY_BLOCKED = "blockedWords"
        private const val KEY_SEEK_STEP = "seekStepSeconds"
        private const val KEY_REMOTE_UP = "remoteUpAction"
        private const val KEY_REMOTE_DOWN = "remoteDownAction"
        private const val KEY_REMOTE_CONFIRM = "remoteConfirmAction"
        private const val KEY_REMOTE_MENU = "remoteMenuAction"
        private const val KEY_STARTUP_AUTOPLAY = "startupAutoplay"
        private const val KEY_SEAMLESS = "seamlessPlayback"
        private const val KEY_POSTER_COLUMNS = "posterColumns"
        private const val KEY_HERO_SOURCE = "heroSource"
        private const val KEY_DANMAKU_COLOR = "danmakuColorMode"
        const val REMOTE_PREVIOUS = "previous"
        const val REMOTE_NEXT = "next"
        const val REMOTE_MENU = "menu"
        const val REMOTE_COMMENTS = "comments"
        const val REMOTE_PLAY_PAUSE = "play_pause"
        const val REMOTE_EPISODES = "episodes"
        const val REMOTE_SLEEP_TIMER = "sleep_timer"
        const val REMOTE_NONE = "none"
        private val REMOTE_ACTIONS = listOf(
            REMOTE_PREVIOUS, REMOTE_NEXT, REMOTE_MENU, REMOTE_COMMENTS,
            REMOTE_PLAY_PAUSE, REMOTE_EPISODES, REMOTE_SLEEP_TIMER, REMOTE_NONE
        )
        val SEEK_STEPS = listOf(10, 15, 30, 60)
        private val DEFAULT_BLOCKED = setOf("加群", "微信", "公众号", "QQ群")

        /** 遥控器动作的标准文案：设置页的下拉项和播放器提示共用，避免两处对不上。 */
        val REMOTE_ACTION_LABELS: List<Pair<String, String>> = listOf(
            REMOTE_PREVIOUS to "上一集",
            REMOTE_NEXT to "下一集",
            REMOTE_PLAY_PAUSE to "播放 / 暂停",
            REMOTE_EPISODES to "打开选集",
            REMOTE_COMMENTS to "弹幕开关",
            REMOTE_MENU to "打开播放设置",
            REMOTE_SLEEP_TIMER to "定时关闭",
            REMOTE_NONE to "无操作",
        )

        fun actionLabel(action: String): String =
            REMOTE_ACTION_LABELS.firstOrNull { it.first == action }?.second ?: "无操作"

        /**
         * 播放器右下角的按键提示。完全由用户设置推导，设置一改这里就跟着变，
         * 不再是一句写死的「左右快进 · 确定暂停 · 下键选集」。
         */
        fun remoteHintLines(
            up: String,
            down: String,
            confirm: String,
            menu: String,
            seekSeconds: Int,
        ): List<String> {
            val first = buildList {
                add("左右 ±${seekSeconds}s")
                if (up != REMOTE_NONE) add("上 ${actionLabel(up)}")
                if (down != REMOTE_NONE) add("下 ${actionLabel(down)}")
            }
            val second = buildList {
                if (confirm != REMOTE_NONE) add("确定 ${actionLabel(confirm)}")
                if (menu != REMOTE_NONE) add("菜单 ${actionLabel(menu)}")
            }
            return listOfNotNull(
                first.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                second.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            )
        }
    }
}
