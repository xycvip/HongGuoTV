// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import com.hongguotv.core.Danmaku
import com.hongguotv.core.DanmakuEmoji
import com.hongguotv.core.DanmakuLoader
import com.hongguotv.core.DanmakuPrefs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 普通 View + 硬件加速 Canvas 实验版；数据与排程逻辑沿用现有实现。 */
class DanmakuCanvasView(context: Context, textSizeSp: Float = 20f, crossMs: Long = 9_000L) :
    View(context), DanmakuRenderer {
    private data class Scheduled(
        val text: String,
        val color: Int,
        val startMs: Long,
        val endMs: Long,
        val lane: Int,
        val widthPx: Float,
    ) {
        /** 从左边缘进入、完全出屏的线性位置；分母恒为 travelMs。 */
        fun xAt(timeMs: Long, viewportWidth: Int): Float =
            viewportWidth - (viewportWidth + widthPx) * (timeMs - startMs).toFloat() / (endMs - startMs).toFloat()
    }

    // 描边衬底 + 纯色填充，只在烤位图时用；每帧绘制走 bitmapPaint。
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }
    private val outline = Paint(fill).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }

    private var scheduled: List<Scheduled> = emptyList()
    private var loader: DanmakuLoader? = null
    private var positionProvider: () -> Long = { 0L }
    private var needMore: (Long) -> Unit = {}
    private var lastPumpAt = 0L
    private var lastBuffered = -1
    private var scheduleAnchorMs = 0L
    private var running = false
    private var playbackActive = false

    private val baseTextSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, textSizeSp, resources.displayMetrics
    )
    private val baseCrossMs = crossMs
    private var travelMs = crossMs
    private var maxRows = Int.MAX_VALUE
    private var colorMode = COLOR_MODE_DEFAULT
    private var opacityAlpha = 255
    private var fontValue = DanmakuPrefs.FONT_DEFAULT
    private var speedValue = DanmakuPrefs.SPEED_DEFAULT
    private var blocked: (String) -> Boolean = { false }
    private val gapPx = 8f * resources.displayMetrics.density

    private var lineHeightPx = 1f
    private var lanes = 0

    private val choreographer = Choreographer.getInstance()
    private var frameScheduled = false
    private var surfaceW = 0
    private var surfaceH = 0
    private var viewAttached = false

    // 软件画布下没有 GPU 帮着缓存字形，每帧 drawText 都要重新扫字形再画两遍，
    // 同屏十几条就顶不住。把「描边 + 填充」首帧烤进一张小 Bitmap，之后每帧只 blit。
    // key 是文字 + 颜色；字号/描边宽度变了整体失效（updateMetrics 负责清）。
    private data class TextKey(val text: String, val color: Int)

    private val textCache = object : LinkedHashMap<TextKey, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextKey, Bitmap>): Boolean {
            if (size > TEXT_CACHE_MAX) {
                eldest.value.recycle()
                return true
            }
            return false
        }
    }
    /** 位图与屏幕 1:1 绘制，不做缩放，所以不加 FILTER——插值只会拖慢且没有收益。 */
    private val bitmapPaint = Paint()
    private var cachedTextSize = 0f
    private var cachedStrokeWidth = 0f
    /** 当前这一集实际用到几条轨道；清屏只清到这里，比整块 lanes 省很多。 */
    private var contentRows = 0
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!viewAttached) return
            val source = loader
            if (running && playbackActive && source != null) {
                // 数据分批异步加载：数量变了就重排一次（弹幕量很小）。
                val buffered = source.buffered
                if (buffered != lastBuffered) {
                    lastBuffered = buffered
                    reschedule()
                }
                val now = positionProvider().coerceAtLeast(0L)
                // 只维护当前位置附近的排程；播放较长视频时避免每次新窗口都重排整集。
                if (now < scheduleAnchorMs - travelMs || now > scheduleAnchorMs + SCHEDULE_AHEAD_MS - travelMs) {
                    scheduleAnchorMs = now
                    reschedule()
                }
                val tick = SystemClock.elapsedRealtime()
                if (now > 0L && tick - lastPumpAt > 1_000L) {
                    lastPumpAt = tick
                    needMore(now)
                }
                // 每个 vsync 都请求窗口硬件加速 Canvas 绘制。
                invalidate()
            }
            if (running && playbackActive && loader != null) scheduleFrame()
        }
    }

    init {
        isFocusable = false
        isClickable = false
        setWillNotDraw(false)
        visibility = GONE
    }

    // ---------------------------------------------------------------- View 生命周期
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewAttached = true
        updateMetrics()
        scheduleFrame()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        surfaceW = width
        surfaceH = height
        updateMetrics()
        reschedule()
    }

    override fun onDetachedFromWindow() {
        viewAttached = false
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
        super.onDetachedFromWindow()
    }

    /** 向屏幕刷新节奏挂一帧回调；标志防止一帧内重复 post 造成回调叠加。 */
    private fun scheduleFrame() {
        if (!viewAttached || !running || !playbackActive || loader == null || frameScheduled) return
        frameScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun updateMetrics() {
        val textSize = baseTextSize * DanmakuPrefs.fontScale(fontValue)
        val strokeWidth = ceil(textSize / 12f).coerceAtLeast(1f)
        // 字号或描边一改，已烤好的位图就失真了，必须整体重烤。
        if (textSize != cachedTextSize || strokeWidth != cachedStrokeWidth) {
            clearTextCache()
            cachedTextSize = textSize
            cachedStrokeWidth = strokeWidth
        }
        fill.textSize = textSize
        outline.textSize = textSize
        outline.strokeWidth = strokeWidth
        fill.alpha = opacityAlpha
        outline.alpha = opacityAlpha
        travelMs = (baseCrossMs * DanmakuPrefs.speedScale(speedValue)).toLong().coerceAtLeast(1L)
        val metrics = fill.fontMetrics
        val textHeightPx = ceil(metrics.descent - metrics.ascent + outline.strokeWidth).coerceAtLeast(1f)
        lineHeightPx = maxOf(textHeightPx, textSize * 1.55f)
        val availableLanes = if (surfaceH <= 0) 0 else (surfaceH / lineHeightPx).toInt()
        lanes = availableLanes.coerceAtMost(maxRows).coerceAtLeast(0)
    }

    /**
     * 把已缓冲弹幕预排到轨道。每批新数据到来、偏好变化或尺寸变化时重排一次；
     * 算法从前往后确定性分配，前面弹幕的轨道不受后续追加影响，不会闪跳。
     */
    private fun reschedule() {
        val source = loader ?: return
        if (surfaceW <= 0 || travelMs <= 0 || lanes <= 0) return
        val anchor = positionProvider().coerceAtLeast(0L)
        scheduleAnchorMs = anchor
        val comments = source.between((anchor - travelMs).coerceAtLeast(0L), anchor + SCHEDULE_AHEAD_MS)
        if (comments.isEmpty()) {
            scheduled = emptyList()
            return
        }
        val prepared = ArrayList<Triple<String, Int, Long>>(comments.size)
        for (comment in comments) {
            val label = DanmakuEmoji.display(comment.text).take(MAX_TEXT_CHARS)
            if (label.isBlank() || blocked(label)) continue
            prepared.add(Triple(label, colorOf(comment), comment.offsetMs))
        }
        prepared.sortBy { it.third }

        val lastOnLane = arrayOfNulls<Scheduled>(lanes)
        val clearanceMs = LongArray(lanes)
        val output = ArrayList<Scheduled>(prepared.size)
        var maxLane = -1
        for ((text, color, at) in prepared) {
            val widthPx = fill.measureText(text)
            if (widthPx <= 0f) continue
            // 同轨两条的安全间隔：前条尾部离开入口后，后条才入轨，避免追尾。
            val clearance = ceil(travelMs * (widthPx + gapPx) / (surfaceW + widthPx)).toLong()
                .coerceAtLeast(MIN_GAP_MS)
            var bestLane = 0
            var bestStart = Long.MAX_VALUE
            for (lane in 0 until lanes) {
                val prev = lastOnLane[lane]
                val earliest = if (prev == null) at
                else maxOf(at, prev.startMs + maxOf(clearanceMs[lane], clearance))
                if (earliest < bestStart) {
                    bestStart = earliest
                    bestLane = lane
                }
            }
            // 轨道全忙且要等太久：丢弃，弹幕宁缺毋滥，不强行叠字。
            if (bestStart > at + MAX_DELAY_MS) continue
            val item = Scheduled(text, color, bestStart, bestStart + travelMs, bestLane, widthPx)
            lastOnLane[bestLane] = item
            clearanceMs[bestLane] = clearance
            output.add(item)
            if (bestLane > maxLane) maxLane = bestLane
        }
        output.sortBy { it.startMs }
        scheduled = output
        // 只增不减：reschedule 后行数可能变少，但上一帧画过的高行仍需覆盖，
        // 所以取历史最大值，等换集时再归零。
        if (maxLane >= 0) contentRows = maxOf(contentRows, maxLane + 1)
    }

    private fun colorOf(comment: Danmaku): Int = when {
        colorMode == COLOR_MODE_COLORFUL -> {
            val seed = if (comment.id.isNotEmpty()) comment.id.hashCode()
            else 31 * comment.offsetMs.hashCode() + comment.text.hashCode()
            COLORFUL_COLORS[Math.floorMod(seed, COLORFUL_COLORS.size)]
        }
        colorMode >= COLOR_MODE_FIRST -> COLOR_VALUES[colorMode - COLOR_MODE_FIRST]
        else -> comment.color ?: Color.WHITE
    }

    /** 二分：第一个 startMs > timeMs 的下标。scheduled 按 startMs 升序。 */
    private fun firstStartingAfter(timeMs: Long): Int {
        var low = 0
        var high = scheduled.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (scheduled[mid].startMs <= timeMs) low = mid + 1 else high = mid
        }
        return low
    }

    /** 每个 vsync 由普通 View 的硬件加速 Canvas 绘制当前可见弹幕。 */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!viewAttached || !running || !playbackActive || loader == null || surfaceW <= 0 || surfaceH <= 0) return
        val now = positionProvider().coerceAtLeast(0L)
        val first = firstStartingAfter(now - travelMs)
        val end = firstStartingAfter(now)
        val clipBottom = (contentRows.coerceAtLeast(1) * lineHeightPx).coerceAtMost(surfaceH.toFloat())
        canvas.save()
        canvas.clipRect(0f, 0f, surfaceW.toFloat(), clipBottom)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (end <= first) {
            canvas.restore()
            return
        }
        bitmapPaint.alpha = opacityAlpha
        val paint = if (opacityAlpha == 255) null else bitmapPaint
        for (i in first until end) {
            val item = scheduled[i]
            val x = item.xAt(now, surfaceW).roundToInt().toFloat()
            val top = (item.lane * lineHeightPx).roundToInt().toFloat()
            canvas.drawBitmap(bitmapFor(item), x, top, paint)
        }
        canvas.restore()
    }

    /**
     * 取（或烤）一句弹幕的位图。描边是黑色、填充是本条颜色，两者一起烤进去；
     * 透明度不烤，留给绘制时 [bitmapPaint].alpha，改偏好不用重烤。
     */
    private fun bitmapFor(item: Scheduled): Bitmap {
        val key = TextKey(item.text, item.color)
        textCache[key]?.let { return it }

        val metrics = fill.fontMetrics
        val stroke = outline.strokeWidth
        val width = ceil(item.widthPx + stroke).toInt().coerceAtLeast(1)
        val height = ceil(metrics.descent - metrics.ascent + stroke).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val baseline = -metrics.ascent + stroke / 2f

        val savedFillAlpha = fill.alpha
        val savedOutlineAlpha = outline.alpha
        fill.color = item.color
        fill.alpha = 255
        outline.alpha = 255
        try {
            canvas.drawText(item.text, stroke / 2f, baseline, outline)
            canvas.drawText(item.text, stroke / 2f, baseline, fill)
        } finally {
            fill.alpha = savedFillAlpha
            outline.alpha = savedOutlineAlpha
        }
        textCache[key] = bitmap
        return bitmap
    }

    /** 字号/描边变化会让位图失真，整体重建；换集时也调用，尽早释放。 */
    private fun clearTextCache() {
        for (bitmap in textCache.values) runCatching { bitmap.recycle() }
        textCache.clear()
    }

    // ---------------------------------------------------------------- 对外接口（保持兼容）

    override fun applyPrefs(opacityPct: Int, font: Int, speed: Int, rows: Int) {
        opacityAlpha = DanmakuPrefs.opacity(opacityPct) * 255 / 100
        fontValue = font
        speedValue = speed
        maxRows = DanmakuPrefs.rows(rows)
        updateMetrics()
        reschedule()
    }

    override fun setColorMode(mode: Int) {
        val normalized = mode.coerceIn(COLOR_MODE_DEFAULT, COLOR_MODE_LAST)
        if (colorMode == normalized) return
        colorMode = normalized
        reschedule()
    }

    override fun setBlockedFilter(filter: (String) -> Boolean) {
        blocked = filter
        reschedule()
    }

    override fun bind(
        loader: DanmakuLoader,
        position: () -> Long,
        needMore: (Long) -> Unit,
        playing: Boolean,
    ) {
        unbind()
        this.loader = loader
        positionProvider = position
        this.needMore = needMore
        lastPumpAt = 0L
        lastBuffered = -1
        scheduleAnchorMs = position().coerceAtLeast(0L)
        running = true
        playbackActive = playing
        visibility = VISIBLE
        updateMetrics()
        reschedule()
        needMore(position().coerceAtLeast(0L))
        viewAttached = isAttachedToWindow
        scheduleFrame()
    }

    /** 暂停/恢复：vsync 循环持续运行，只切换绘制开关即可，无需重启线程。 */
    override fun setPlaybackActive(active: Boolean) {
        if (!running || playbackActive == active) return
        playbackActive = active
        if (active && loader != null && viewAttached) {
            scheduleFrame()
        } else {
            choreographer.removeFrameCallback(frameCallback)
            frameScheduled = false
        }
    }

    /** 位置按绝对媒体时间计算，seek 后自然落到正确位置，无需清空。 */
    override fun seekTo(positionMs: Long) {
        lastPumpAt = 0L
        // seek 后画面跳变，下一帧必须重画（哪怕是空帧），否则残留上一处的内容。
    }

    override fun unbind() {
        running = false
        playbackActive = false
        loader = null
        needMore = {}
        scheduled = emptyList()
        lastBuffered = -1
        scheduleAnchorMs = 0L
        positionProvider = { 0L }
        contentRows = 0
        clearTextCache()
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
        visibility = GONE
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
        /** 同轨道两条弹幕的最小入场间隔。 */
        private const val MIN_GAP_MS = 400L
        /** 轨道挤满时最多顺延多久，太久就丢弃，避免弹幕远离剧情。 */
        private const val MAX_DELAY_MS = 3_000L
        private const val MAX_TEXT_CHARS = 80
        /** Bound track scheduling to a rolling two-minute window plus currently visible tails. */
        private const val SCHEDULE_AHEAD_MS = 120_000L
        /**
         * 文字位图缓存条数。一条弹幕在屏 9 秒，同屏可见通常二三十条，
         * 取 64 条既覆盖当前窗口、又不会把整集几千条都烤进内存。
         */
        private const val TEXT_CACHE_MAX = 64
    }
}
