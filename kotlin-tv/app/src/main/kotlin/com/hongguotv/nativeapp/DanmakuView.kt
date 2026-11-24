// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.SystemClock
import android.util.TypedValue
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.hongguotv.core.Danmaku
import com.hongguotv.core.DanmakuEmoji
import com.hongguotv.core.DanmakuLoader
import com.hongguotv.core.DanmakuPrefs
import kotlin.math.ceil

/**
 * Lightweight rolling-comment overlay. The playback position is the clock: a comment enters
 * from the right edge when playback crosses its anchor and drifts left at a fixed speed, so
 * pausing, seeking and playback-speed changes need no extra handling. Comments are assigned
 * to the first track whose previous comment has fully entered; when every track is busy the
 * comment is dropped, matching common danmaku behavior.
 *
 * 渲染方式：独立 SurfaceView（setZOrderMediaOverlay）+ 独立渲染线程。
 * 之前是普通 View 叠在 PlayerView 的 SurfaceView 上，弹幕每动一帧都会强制整个窗口走
 * GPU 合成（4K 输出时每帧八百万像素），廉价电视盒子直接掉帧。换成独立 surface 后
 * 视频留在自己的硬件层，弹幕只在顶部轨道带上贴图，两者由 HWC 叠加，不再互相拖累。
 *
 * 文字仍在进场时一次性烤成小位图，滚动阶段只做 drawBitmap；重绘固定 30fps。
 */
class DanmakuView(context: Context, textSizeSp: Float = 20f, crossMs: Long = 9_000L) :
    SurfaceView(context), SurfaceHolder.Callback {
    private data class Active(
        val danmaku: Danmaku,
        val label: String,
        val bitmap: Bitmap,
        val width: Float,
        val track: Int,
    )

    private val lock = Any()
    private val active = ArrayList<Active>()
    @Volatile
    var loader: DanmakuLoader? = null
        private set
    private var positionProvider: () -> Long = { 0L }
    private var needMore: (Long) -> Unit = {}
    private var lastPumpAt = 0L
    private var lastEmit = -1L
    private var cachedPosition = 0L
    @Volatile
    private var running = false
    @Volatile
    private var playbackActive = false
    private var needsClear = false
    private var pxPerMs = 0f
    private var tracks = 1
    private var trackHeight = 0f
    private var surfaceW = 0
    private var surfaceH = 0
    private var surfaceAlive = false
    private var renderThread: Thread? = null

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

    /** 文字渲染专用的离屏画笔：阴影只在生成位图时算一次。 */
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = baseTextSize
        setShadowLayer((baseTextSize / 7f).coerceAtLeast(1f), 0f, baseTextSize / 26f, Color.BLACK)
    }
    // 位图按 1:1 贴到 surface 上，关掉采样能省一部分 GPU/CPU。
    private val bitmapPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val dirtyRect = Rect()
    private var textPadding = 0f

    init {
        isFocusable = false
        // 关键：让弹幕 surface 压在视频 surface 之上、但仍在窗口（Compose 控件）之下。
        setZOrderMediaOverlay(true)
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        visibility = GONE
        updateMetrics()
    }

    private fun updateMetrics() {
        textPadding = ceil(textPaint.textSize * 0.20f)
    }

    /**
     * Applies the user's rendering preferences. Layout-affecting changes (font size, row cap)
     * clear on-screen comments; opacity and speed apply to what is already flying.
     */
    fun applyPrefs(opacityPct: Int, font: Int, speed: Int, rows: Int) {
        synchronized(lock) {
            val oldTextSize = textPaint.textSize
            val oldMaxTracks = maxTracks
            opacityAlpha = DanmakuPrefs.opacity(opacityPct) * 255 / 100
            // 位图里保持满不透明，透明度统一在贴图时乘，避免 alpha 被乘两次变淡。
            textPaint.alpha = 255
            textPaint.textSize = baseTextSize * DanmakuPrefs.fontScale(font)
            textPaint.setShadowLayer(
                (textPaint.textSize / 7f).coerceAtLeast(1f),
                0f,
                textPaint.textSize / 26f,
                Color.BLACK,
            )
            updateMetrics()
            currentCrossMs = (baseCrossMs * DanmakuPrefs.speedScale(speed)).toLong().coerceAtLeast(1)
            maxTracks = DanmakuPrefs.rows(rows)
            relayout()
            if (textPaint.textSize != oldTextSize || maxTracks != oldMaxTracks) {
                clearActive()
            }
        }
    }

    /** 默认保留接口原色；多彩稳定分配颜色；固定颜色模式统一使用用户选择的颜色。 */
    fun setColorMode(mode: Int) {
        val normalized = mode.coerceIn(COLOR_MODE_DEFAULT, COLOR_MODE_LAST)
        synchronized(lock) {
            if (colorMode == normalized) return
            colorMode = normalized
            // 位图里已经烤进颜色，换模式后按新颜色重烤，避免旧颜色留在屏幕上。
            if (active.isNotEmpty()) {
                val rebuilt = active.mapNotNull { item ->
                    val bitmap = renderText(item.label, colorOf(item.danmaku)) ?: return@mapNotNull null
                    Active(item.danmaku, item.label, bitmap, item.width, item.track)
                }
                clearActive()
                active.addAll(rebuilt)
            }
            needsClear = true
        }
    }

    private fun relayout() {
        if (surfaceW <= 0 || surfaceH <= 0 || currentCrossMs <= 0) return
        pxPerMs = surfaceW.toFloat() / currentCrossMs
        trackHeight = textPaint.textSize * 1.62f
        tracks = (surfaceH / trackHeight).toInt().coerceAtLeast(1).coerceAtMost(maxTracks)
    }

    /**
     * [position] reports playback milliseconds; [needMore] is raised on the render thread about
     * once a second while playback advances — the host should schedule [DanmakuLoader.loadUpTo]
     * off-thread.
     */
    fun bind(loader: DanmakuLoader, position: () -> Long, needMore: (Long) -> Unit = {}, playing: Boolean = true) {
        unbind()
        synchronized(lock) {
            this.loader = loader
            positionProvider = position
            this.needMore = needMore
            lastEmit = position().coerceAtLeast(0)
            cachedPosition = lastEmit
            lastPumpAt = 0L
            running = true
            playbackActive = playing
            needsClear = true
        }
        visibility = VISIBLE
    }

    /** Stop drawing while video is paused; resume from the media clock without drifting. */
    fun setPlaybackActive(active: Boolean) {
        if (!running || playbackActive == active) return
        playbackActive = active
        if (active) {
            // 暂停期间媒体时钟可能被 seek 过，先对齐一次，避免恢复瞬间弹幕乱飞。
            synchronized(lock) {
                cachedPosition = positionProvider().coerceAtLeast(0)
                if (lastEmit < 0) lastEmit = cachedPosition
                lastPumpAt = 0L
            }
        }
    }

    fun setBlockedFilter(filter: (String) -> Boolean) {
        synchronized(lock) {
            blocked = filter
            // 只回收命中的那几条：整表清空会把还要继续显示的位图一起 recycle 掉。
            val removed = active.filter { blocked(it.label) }
            if (removed.isNotEmpty()) {
                active.removeAll(removed)
                removed.forEach { runCatching { it.bitmap.recycle() } }
            }
            needsClear = true
        }
    }

    fun unbind() {
        running = false
        playbackActive = false
        synchronized(lock) {
            loader = null
            needMore = {}
            clearActive()
            lastEmit = -1L
        }
        visibility = GONE
    }

    /** Playback jumped; comments on screen belong to the old position. */
    fun seekTo(positionMs: Long) {
        synchronized(lock) {
            clearActive()
            lastEmit = positionMs.coerceAtLeast(0)
            cachedPosition = lastEmit
            needsClear = true
        }
    }

    // ------------------------------------------------------------ surface 生命周期

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAlive = true
        needsClear = true
        startRenderThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(lock) {
            surfaceW = width
            surfaceH = height
            relayout()
            clearActive()
            lastEmit = -1L
            needsClear = true
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // 不在这里清 renderThread：线程可能正卡在 lockCanvas 上，
        // 让它自己看到 surfaceAlive=false 后退出，避免 surface 重建时出现两条线程。
        surfaceAlive = false
    }

    override fun onDetachedFromWindow() {
        surfaceAlive = false
        super.onDetachedFromWindow()
    }

    private fun startRenderThread() {
        val existing = renderThread
        if (existing != null && existing.isAlive) return
        val thread = Thread({
            try {
            while (surfaceAlive) {
                if (!running || !playbackActive) {
                    SystemClock.sleep(60)
                    continue
                }
                val startedAt = SystemClock.uptimeMillis()
                val empty = synchronized(lock) {
                    step()
                    active.isEmpty() && !needsClear
                }
                if (!empty) drawFrame()
                val cost = SystemClock.uptimeMillis() - startedAt
                SystemClock.sleep((FRAME_INTERVAL_MS - cost).coerceAtLeast(4L))
            }
            } finally {
                if (renderThread === Thread.currentThread()) renderThread = null
            }
        }, "danmaku-surface")
        thread.priority = Thread.NORM_PRIORITY + 1
        renderThread = thread
        thread.start()
    }

    private fun x(item: Active, position: Long) = surfaceW - (position - item.danmaku.offsetMs) * pxPerMs

    /** 推进媒体时钟、放行新弹幕、回收出屏弹幕。调用方需持有 [lock]。 */
    private fun step() {
        val loader = loader ?: return
        if (surfaceW == 0) return
        val position = positionProvider().coerceAtLeast(0)
        cachedPosition = position
        if (lastEmit < 0) lastEmit = position
        if (position < lastEmit - SEEK_TOLERANCE_MS || position - lastEmit > SEEK_FORWARD_MS) {
            clearActive()
            lastEmit = position
            needsClear = true
        }
        if (position > lastEmit) {
            // 一次追帧最多放行这么多条：缓存 miss 后突然补一大批时，别在单帧里烤几十张位图。
            var budget = MAX_EMITS_PER_FRAME
            for (comment in loader.between(lastEmit, position)) {
                if (budget-- <= 0) break
                emit(comment, position)
            }
            lastEmit = position
        }
        // 倒序删除，避免每帧 removeAll 分配迭代器。
        for (index in active.indices.reversed()) {
            val item = active[index]
            if (x(item, position) + item.width < 0) {
                active.removeAt(index)
                runCatching { item.bitmap.recycle() }
                needsClear = true
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (position > 0 && now - lastPumpAt > 1_000) {
            lastPumpAt = now
            needMore(position)
        }
    }

    private fun emit(comment: Danmaku, position: Long) {
        if (pxPerMs <= 0f) return
        if (blocked(comment.text)) return
        // Expand emoji codes once here: measure and draw must see the same text.
        val label = DanmakuEmoji.display(comment.text).take(MAX_TEXT_CHARS)
        val widthF = surfaceW.toFloat()
        for (track in 0 until tracks) {
            val last = active.lastOrNull { it.track == track }
            if (last == null || x(last, position) + last.width < widthF - gapPx) {
                val bitmap = renderText(label, colorOf(comment)) ?: return
                active.add(Active(comment, label, bitmap, textPaint.measureText(label), track))
                return
            }
        }
        // Every track is busy; drop the comment rather than overlapping.
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

    /** 把一条弹幕（含阴影）画进小位图；滚动时只剩贴图，不再触发文字光栅化。 */
    private fun renderText(label: String, color: Int): Bitmap? {
        val textWidth = textPaint.measureText(label)
        if (textWidth <= 0f) return null
        val metrics = textPaint.fontMetrics
        val bitmapWidth = ceil(textWidth + textPadding * 2f).toInt().coerceAtLeast(1)
        val bitmapHeight = ceil(metrics.descent - metrics.ascent + textPadding * 2f).toInt().coerceAtLeast(1)
        return runCatching {
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val previous = textPaint.color
            textPaint.color = color
            canvas.drawText(label, textPadding, textPadding - metrics.ascent, textPaint)
            textPaint.color = previous
            bitmap
        }.getOrNull()
    }

    private fun clearActive() {
        active.forEach { runCatching { it.bitmap.recycle() } }
        active.clear()
        needsClear = true
    }

    /** 在独立 surface 上重绘顶部轨道带；视频画面完全不受影响。 */
    private fun drawFrame() {
        if (!surfaceAlive || surfaceW <= 0 || surfaceH <= 0) return
        val surfaceHolder = holder
        val bottom = (tracks * trackHeight).toInt() + 2
        dirtyRect.set(0, 0, surfaceW, bottom.coerceIn(1, surfaceH))
        val canvas = try {
            // 硬件画笔：文字位图由 GPU 直接合成，省掉 lockCanvas 的 CPU 光栅化与整块上传。
            // 低端盒子上个别机型不支持，回退到软件画笔。
            surfaceHolder.lockHardwareCanvas()
        } catch (_: Throwable) {
            try {
                surfaceHolder.lockCanvas(dirtyRect)
            } catch (_: Exception) {
                null
            }
        } ?: return
        try {
            synchronized(lock) {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                needsClear = false
                if (active.isEmpty()) return@synchronized
                val position = cachedPosition
                val widthF = surfaceW.toFloat()
                bitmapPaint.alpha = opacityAlpha
                for (item in active) {
                    val left = x(item, position)
                    if (left > widthF || left + item.width < 0) continue
                    canvas.drawBitmap(item.bitmap, left - textPadding, item.track * trackHeight, bitmapPaint)
                }
            }
        } catch (_: Exception) {
            // surface 被回收/尺寸变化时丢掉这一帧即可。
        } finally {
            runCatching { surfaceHolder.unlockCanvasAndPost(canvas) }
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
        /**
         * 24fps：弹幕是匀速平移，25/30fps 的差别肉眼几乎看不出来，
         * 但每帧的合成/上传成本直接少 20%，低端盒子更稳。
         */
        private const val FRAME_INTERVAL_MS = 41L
        private const val MAX_EMITS_PER_FRAME = 4
        private const val MAX_TEXT_CHARS = 80
    }
}
