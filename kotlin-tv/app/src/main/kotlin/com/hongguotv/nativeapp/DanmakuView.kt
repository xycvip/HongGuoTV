// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.hongguotv.core.Danmaku
import com.hongguotv.core.DanmakuEmoji
import com.hongguotv.core.DanmakuLoader
import com.hongguotv.core.DanmakuPrefs
import kotlin.math.ceil

/**
 * 滚动弹幕覆盖层。
 *
 * 渲染机制：独立 SurfaceView + [Choreographer] 对齐 vsync 驱动 + 预排程 drawText。
 *
 * 两个关键点缺一不可：
 * 1) 【节奏】用 Choreographer 而非 sleep 计时。sleep(33ms) 既不能整除 60/90/120Hz，
 *    精度又在 35~45ms 飘，几条弹幕也一顿一顿跳；vsync 回调帧间隔恒定，滚动才丝滑。
 * 2) 【载体】用 SurfaceView 而非普通 View。普通 View 每帧 invalidate 会强制整个 window
 *    重合成；本项目是双播放器叠放，两层全屏视频 SurfaceView 会被一起拖垮（视频卡死/错位）。
 *    SurfaceView 是独立硬件层，弹幕重绘不触发 window 合成，视频完全不受影响。
 *
 * 弹幕一次性预排程：数据每批到来时把已缓冲弹幕排到轨道、算好 startMs/endMs，
 * 每帧只做二分定位可见区间 + drawText，不做逐帧轨道分配/位图烤制。
 */
class DanmakuView(context: Context, textSizeSp: Float = 20f, crossMs: Long = 9_000L) :
    SurfaceView(context), SurfaceHolder.Callback {
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

    // 文字用描边衬底，每帧直接 drawText，不做位图烤制——同屏弹幕就那么几条，成本可忽略。
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
    private var firstBaseline = 0f
    private var lanes = 0

    // SurfaceView 渲染调度：Choreographer 对齐屏幕 vsync，帧间隔恒定。
    private val choreographer = Choreographer.getInstance()
    private var frameScheduled = false
    private var surfaceW = 0
    private var surfaceH = 0
    private var surfaceAlive = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!surfaceAlive) return
            val source = loader
            if (running && playbackActive && source != null) {
                // 数据分批异步加载：数量变了就重排一次（弹幕量很小）。
                val buffered = source.buffered
                if (buffered != lastBuffered) {
                    lastBuffered = buffered
                    reschedule()
                }
                val now = positionProvider().coerceAtLeast(0L)
                val tick = SystemClock.elapsedRealtime()
                if (now > 0L && tick - lastPumpAt > 1_000L) {
                    lastPumpAt = tick
                    needMore(now)
                }
                drawFrame(now)
            }
            scheduleFrame()
        }
    }

    init {
        isFocusable = false
        // 弹幕 surface 压在视频 surface 之上、Compose 控制层之下。
        setZOrderMediaOverlay(true)
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        visibility = GONE
    }

    // ---------------------------------------------------------------- surface 生命周期
    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAlive = true
        updateMetrics()
        scheduleFrame()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        updateMetrics()
        reschedule()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAlive = false
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
    }

    override fun onDetachedFromWindow() {
        surfaceAlive = false
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
        super.onDetachedFromWindow()
    }

    /** 向屏幕刷新节奏挂一帧回调；标志防止一帧内重复 post 造成回调叠加。 */
    private fun scheduleFrame() {
        if (!surfaceAlive || frameScheduled) return
        frameScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun updateMetrics() {
        val textSize = baseTextSize * DanmakuPrefs.fontScale(fontValue)
        fill.textSize = textSize
        outline.textSize = textSize
        outline.strokeWidth = ceil(textSize / 12f).coerceAtLeast(1f)
        fill.alpha = opacityAlpha
        outline.alpha = opacityAlpha
        travelMs = (baseCrossMs * DanmakuPrefs.speedScale(speedValue)).toLong().coerceAtLeast(1L)
        val metrics = fill.fontMetrics
        val textHeightPx = ceil(metrics.descent - metrics.ascent + outline.strokeWidth).coerceAtLeast(1f)
        lineHeightPx = maxOf(textHeightPx, textSize * 1.55f)
        firstBaseline = -metrics.ascent + outline.strokeWidth / 2f
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
        val comments = source.between(0L, Long.MAX_VALUE)
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
        }
        output.sortBy { it.startMs }
        scheduled = output
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

    /**
     * 在独立 surface 上画一帧。硬件画布由 GPU 合成，CPU 零光栅化，
     * 且不触发 window 重合成——双播放器视频层完全不受影响。
     * 调度节奏由 [frameCallback] 的 Choreographer vsync 回调负责，这里只管画。
     */
    private fun drawFrame(now: Long) {
        if (!surfaceAlive || surfaceW <= 0 || surfaceH <= 0) return
        val canvas = try {
            holder.lockHardwareCanvas()
        } catch (_: Throwable) {
            // 个别盒子硬件画布不可用，降级软件画布 + 脏区。
            try {
                holder.lockCanvas()
            } catch (_: Exception) {
                return
            }
        } ?: return
        try {
            // 只清弹幕轨道带，避免每帧清整屏；布局变化时 lastBuffered 重排已覆盖。
            val clipBottom = (lanes * lineHeightPx).coerceAtMost(surfaceH.toFloat())
            canvas.save()
            canvas.clipRect(0f, 0f, surfaceW.toFloat(), clipBottom)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // 可见区间：startMs 落在 (now - travelMs, now]。
            val from = now - travelMs
            val first = firstStartingAfter(from)
            val end = firstStartingAfter(now)
            for (i in first until end) {
                val item = scheduled[i]
                val x = item.xAt(now, surfaceW)
                val y = firstBaseline + item.lane * lineHeightPx
                fill.color = item.color
                canvas.drawText(item.text, x, y, outline)
                canvas.drawText(item.text, x, y, fill)
            }
            canvas.restore()
        } catch (_: Exception) {
            // surface 尺寸变化/回收时丢掉这一帧即可。
        } finally {
            runCatching { holder.unlockCanvasAndPost(canvas) }
        }
    }

    // ---------------------------------------------------------------- 对外接口（保持兼容）

    fun applyPrefs(opacityPct: Int, font: Int, speed: Int, rows: Int) {
        opacityAlpha = DanmakuPrefs.opacity(opacityPct) * 255 / 100
        fontValue = font
        speedValue = speed
        maxRows = DanmakuPrefs.rows(rows)
        updateMetrics()
        reschedule()
    }

    fun setColorMode(mode: Int) {
        val normalized = mode.coerceIn(COLOR_MODE_DEFAULT, COLOR_MODE_LAST)
        if (colorMode == normalized) return
        colorMode = normalized
        reschedule()
    }

    fun setBlockedFilter(filter: (String) -> Boolean) {
        blocked = filter
        reschedule()
    }

    fun bind(
        loader: DanmakuLoader,
        position: () -> Long,
        needMore: (Long) -> Unit = {},
        playing: Boolean = true,
    ) {
        unbind()
        this.loader = loader
        positionProvider = position
        this.needMore = needMore
        lastPumpAt = 0L
        lastBuffered = -1
        running = true
        playbackActive = playing
        visibility = VISIBLE
        updateMetrics()
        reschedule()
        needMore(position().coerceAtLeast(0L))
        // 启动 vsync 循环；surface 可能已存在（bind 晚于 surfaceCreated），这里补一次调度。
        surfaceAlive = holder.surface.isValid
        scheduleFrame()
    }

    /** 暂停/恢复：vsync 循环持续运行，只切换绘制开关即可，无需重启线程。 */
    fun setPlaybackActive(active: Boolean) {
        if (!running || playbackActive == active) return
        playbackActive = active
    }

    /** 位置按绝对媒体时间计算，seek 后自然落到正确位置，无需清空。 */
    fun seekTo(positionMs: Long) {
        lastPumpAt = 0L
    }

    fun unbind() {
        running = false
        playbackActive = false
        loader = null
        needMore = {}
        scheduled = emptyList()
        lastBuffered = -1
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
    }
}
