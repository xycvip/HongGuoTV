// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.util.TypedValue
import com.hongguotv.core.Danmaku
import com.hongguotv.core.DanmakuEmoji
import com.hongguotv.core.DanmakuLoader
import com.hongguotv.core.DanmakuPrefs

/**
 * Lightweight rolling-comment overlay. The playback position is the clock: a comment enters
 * from the right edge when playback crosses its anchor and drifts left at a fixed speed, so
 * pausing, seeking and playback-speed changes need no extra handling. Comments are assigned
 * to the first track whose previous comment has fully entered; when every track is busy the
 * comment is dropped, matching common danmaku behavior.
 */
class DanmakuView(context: Context, textSizeSp: Float = 20f, crossMs: Long = 9_000L) : View(context) {
    private data class Active(val danmaku: Danmaku, val text: String, val width: Float, val track: Int)

    private val active = ArrayList<Active>()
    var loader: DanmakuLoader? = null
        private set
    private var positionProvider: () -> Long = { 0L }
    private var needMore: (Long) -> Unit = {}
    private var lastPumpAt = 0L
    private var lastEmit = -1L
    private var running = false
    private var playbackActive = false
    private var dirty = false
    private var pxPerMs = 0f
    private var tracks = 1
    private var trackHeight = 0f
    private val baseTextSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        textSizeSp,
        resources.displayMetrics
    )
    private val baseCrossMs = crossMs
    private var currentCrossMs = crossMs
    private var maxTracks = Int.MAX_VALUE
    private var colorMode = COLOR_MODE_DEFAULT
    private var opacityAlpha = 255
    /** 命中屏蔽词的弹幕直接丢弃，不再参与轨道分配。 */
    private var blocked: (String) -> Boolean = { false }
    private val gapPx = 24 * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = baseTextSize
        setShadowLayer(textSize / 6f, 0f, 0f, Color.BLACK)
    }

    init {
        isFocusable = false
        visibility = GONE
    }

    /**
     * Applies the user's rendering preferences. Layout-affecting changes (font size, row cap)
     * clear on-screen comments; opacity and speed apply to what is already flying.
     */
    fun applyPrefs(opacityPct: Int, font: Int, speed: Int, rows: Int) {
        val oldTextSize = paint.textSize
        val oldMaxTracks = maxTracks
        opacityAlpha = DanmakuPrefs.opacity(opacityPct) * 255 / 100
        paint.alpha = opacityAlpha
        paint.textSize = baseTextSize * DanmakuPrefs.fontScale(font)
        paint.setShadowLayer(paint.textSize / 6f, 0f, 0f, Color.BLACK)
        currentCrossMs = (baseCrossMs * DanmakuPrefs.speedScale(speed)).toLong().coerceAtLeast(1)
        maxTracks = DanmakuPrefs.rows(rows)
        relayout()
        if (paint.textSize != oldTextSize || maxTracks != oldMaxTracks) {
            active.clear()
            dirty = true
        }
        invalidate()
    }

    /** 默认保留接口原色；多彩稳定分配颜色；固定颜色模式统一使用用户选择的颜色。 */
    fun setColorMode(mode: Int) {
        val normalized = mode.coerceIn(COLOR_MODE_DEFAULT, COLOR_MODE_LAST)
        if (colorMode == normalized) return
        colorMode = normalized
        invalidate()
    }

    private fun relayout() {
        if (width <= 0 || height <= 0 || currentCrossMs <= 0) return
        pxPerMs = width.toFloat() / currentCrossMs
        trackHeight = paint.textSize * 1.5f
        tracks = (height / trackHeight).toInt().coerceAtLeast(1).coerceAtMost(maxTracks)
    }

    /**
     * [position] reports playback milliseconds; [needMore] is raised on the UI thread about once
     * a second while playback advances — the host should schedule [DanmakuLoader.loadUpTo] off-thread.
     */
    fun bind(loader: DanmakuLoader, position: () -> Long, needMore: (Long) -> Unit = {}, playing: Boolean = true) {
        unbind()
        this.loader = loader
        positionProvider = position
        this.needMore = needMore
        lastEmit = position().coerceAtLeast(0)
        lastPumpAt = 0L
        running = true
        playbackActive = playing
        visibility = VISIBLE
        if(playbackActive) scheduleFrame()
        invalidate()
    }

    /** Stop the frame loop while video is paused; resume from the media clock without drifting. */
    fun setPlaybackActive(active: Boolean) {
        if(!running || playbackActive==active) return
        playbackActive=active
        if(active) {
            lastPumpAt=0L
            scheduleFrame()
        } else {
            removeCallbacks(frame)
            invalidate()
        }
    }

    fun setBlockedFilter(filter: (String) -> Boolean) {
        blocked = filter
        active.removeAll { blocked(it.text) }
        dirty = true
        invalidate()
    }

    fun unbind() {
        running = false
        playbackActive = false
        removeCallbacks(frame)
        loader = null
        needMore = {}
        active.clear()
        lastEmit = -1L
        visibility = GONE
    }

    /** Playback jumped; comments on screen belong to the old position. */
    fun seekTo(positionMs: Long) {
        active.clear()
        lastEmit = positionMs.coerceAtLeast(0)
        dirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
        active.clear()
        lastEmit = -1L
    }

    private fun scheduleFrame() {
        if(!running || !playbackActive) return
        // Full frame rate is needed only while comments are moving; otherwise poll the media
        // clock at 4 Hz so upcoming comments still enter on time without burning idle CPU.
        if(active.isEmpty()) postDelayed(frame,250L) else postOnAnimation(frame)
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!running || !playbackActive) return
            step()
            scheduleFrame()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if(running && playbackActive) scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    private fun x(item: Active, position: Long) = width - (position - item.danmaku.offsetMs) * pxPerMs

    private fun step() {
        if(!running || !playbackActive) return
        val loader = loader ?: return
        if (width == 0) return
        val position = positionProvider().coerceAtLeast(0)
        if (lastEmit < 0) lastEmit = position
        if (position < lastEmit - SEEK_TOLERANCE_MS || position - lastEmit > SEEK_FORWARD_MS) {
            active.clear()
            lastEmit = position
            dirty = true
        }
        if (position > lastEmit) {
            for (comment in loader.between(lastEmit, position)) emit(comment, position)
            lastEmit = position
            dirty = true
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (position > 0 && now - lastPumpAt > 1_000) {
            lastPumpAt = now
            needMore(position)
        }
        val widthF = width.toFloat()
        if (active.removeAll { x(it, position) + it.width < 0 }) dirty = true
        // Active comments move with the media clock every frame, even when no new comment enters.
        if (dirty || active.isNotEmpty()) {
            dirty = false
            invalidate()
        }
    }

    private fun emit(comment: Danmaku, position: Long) {
        if (pxPerMs <= 0f) return
        if (blocked(comment.text)) return
        // Expand emoji codes once here: measure and draw must see the same text.
        val text = DanmakuEmoji.display(comment.text)
        val textWidth = paint.measureText(text)
        val widthF = width.toFloat()
        for (track in 0 until tracks) {
            val last = active.lastOrNull { it.track == track }
            if (last == null || x(last, position) + last.width < widthF - gapPx) {
                active.add(Active(comment, text, textWidth, track))
                return
            }
        }
        // Every track is busy; drop the comment rather than overlapping.
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (active.isEmpty()) return
        val position = positionProvider().coerceAtLeast(0)
        val widthF = width.toFloat()
        for (item in active) {
            val x = x(item, position)
            if (x > widthF || x + item.width < 0) continue
            paint.color = when {
                colorMode == COLOR_MODE_COLORFUL -> {
                    val seed = if (item.danmaku.id.isNotEmpty()) item.danmaku.id.hashCode()
                        else 31 * item.danmaku.offsetMs.hashCode() + item.danmaku.text.hashCode()
                    COLORFUL_COLORS[Math.floorMod(seed, COLORFUL_COLORS.size)]
                }
                colorMode >= COLOR_MODE_FIRST -> COLOR_VALUES[colorMode - COLOR_MODE_FIRST]
                else -> item.danmaku.color ?: Color.WHITE
            }
            paint.alpha = opacityAlpha
            canvas.drawText(item.text, x, item.track * trackHeight + paint.textSize, paint)
        }
    }

    companion object {
        const val COLOR_MODE_DEFAULT = 0
        const val COLOR_MODE_COLORFUL = 1
        const val COLOR_MODE_FIRST = 2
        const val COLOR_MODE_LAST = COLOR_MODE_FIRST + 7
        val COLOR_NAMES = listOf("白色", "红色", "橙色", "黄色", "绿色", "青色", "蓝色", "紫色")
        private val COLOR_VALUES = intArrayOf(
            Color.WHITE,
            Color.rgb(255, 91, 98),
            Color.rgb(255, 157, 66),
            Color.rgb(255, 213, 74),
            Color.rgb(89, 217, 142),
            Color.rgb(77, 208, 225),
            Color.rgb(100, 169, 255),
            Color.rgb(199, 146, 234),
        )
        private val COLORFUL_COLORS = COLOR_VALUES.drop(1).toIntArray()
        private const val SEEK_TOLERANCE_MS = 250L
        private const val SEEK_FORWARD_MS = 1_000L
    }
}
