// SPDX-License-Identifier: GPL-3.0-only
package com.x.hongguotv.libmpv

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.hongguotv.core.RemoteVideo
import is.xyz.mpv.MPVLib
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Embedded libmpv output surface.
 *
 * This is deliberately independent from Media3/ExoPlayer and the existing FFmpeg
 * renderer. A single libmpv context is supported because the upstream JNI bridge
 * owns one process-global mpv_handle.
 */
class LibMpvPlaybackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, MPVLib.EventObserver, MPVLib.LogObserver, Closeable {

    enum class HardwareMode(val option: String) {
        AUTO("auto"),
        HARDWARE("mediacodec,mediacodec-copy"),
        SOFTWARE("no")
    }

    interface Listener {
        fun onState(state: String) {}
        fun onPosition(positionSeconds: Double, durationSeconds: Double) {}
        fun onDecoder(name: String) {}
        fun onLog(level: Int, message: String) {}
    }

    @Volatile var listener: Listener? = null
    @Volatile private var initialized = false
    @Volatile private var surfaceAttached = false
    @Volatile private var pendingUrl: String? = null
    @Volatile private var pendingStartPositionSeconds: Double = 0.0
    @Volatile private var startAutoplay: Boolean = true
    private var bridge: LibMpvHttpBridge? = null
    private var currentMode = HardwareMode.HARDWARE
    private val destroyed = AtomicBoolean(false)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        holder.setFormat(PixelFormat.OPAQUE)
    }

    /**
     * Initializes the real libmpv runtime and points it at the app's decrypted
     * RemoteVideo stream through a loopback range bridge.
     * The caller retains ownership of [video] and must close it after this view.
     */
    @Synchronized
    fun initialize(
        video: RemoteVideo,
        mode: HardwareMode = HardwareMode.HARDWARE,
        startPositionSeconds: Double = 0.0,
        autoplay: Boolean = true
    ) {
        check(!initialized) { "libmpv view is already initialized" }
        check(!destroyed.get()) { "libmpv view has been destroyed" }
        check(engineClaimed.compareAndSet(false, true)) {
            "libmpv currently supports one active playback session"
        }
        try {
            currentMode = mode
            val localBridge = LibMpvHttpBridge(video) { Log.i(TAG, it) }
            val localUrl = localBridge.start()
            bridge = localBridge

            MPVLib.create(context.applicationContext)
            setOption("config", "no")
            setOption("profile", "fast")
            setOption("vo", "gpu")
            setOption("gpu-context", "android")
            setOption("opengl-es", "yes")
            setOption("hwdec", mode.option)
            setOption("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            setOption("ao", "audiotrack,opensles")
            setOption("audio-set-media-role", "yes")
            setOption("tls-verify", "yes")
            setOption("demuxer-max-bytes", "67108864")
            setOption("demuxer-max-back-bytes", "16777216")
            setOption("cache", "yes")
            MPVLib.init()
            setOption("force-window", "no")
            setOption("idle", "once")
            MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            MPVLib.addObserver(this)
            MPVLib.addLogObserver(this)
            pendingUrl = localUrl
            holder.addCallback(this)
            initialized = true
            post { requestFocus() }
            Log.i(TAG, "libmpv initialized mode=$mode url=$localUrl")
        } catch (problem: Throwable) {
            runCatching { MPVLib.removeObserver(this) }
            runCatching { MPVLib.removeLogObserver(this) }
            runCatching { MPVLib.destroy() }
            bridge?.close()
            bridge = null
            engineClaimed.set(false)
            if (problem is VirtualMachineError || problem is ThreadDeath) throw problem
            throw IllegalStateException("libmpv initialization failed: ${problem.message}", problem)
        }
    }

    private fun setOption(name: String, value: String) {
        val result = MPVLib.setOptionString(name, value)
        if (result < 0) Log.w(TAG, "mpv option rejected: $name=$value result=$result")
    }

    fun play() {
        check(initialized)
        MPVLib.setPropertyBoolean("pause", false)
    }

    fun pause() {
        if (initialized) MPVLib.setPropertyBoolean("pause", true)
    }

    fun togglePause() {
        if (initialized) MPVLib.command(arrayOf("cycle", "pause"))
    }

    fun currentPositionSeconds(): Double =
        if (initialized) MPVLib.getPropertyDouble("time-pos") ?: 0.0 else 0.0

    fun seekTo(seconds: Double) {
        if (initialized && seconds.isFinite() && seconds >= 0.0) {
            MPVLib.command(arrayOf("seek", seconds.toString(), "absolute+exact"))
        }
    }

    fun setSpeed(speed: Double) {
        if (initialized && speed in 0.25..4.0) MPVLib.setPropertyDouble("speed", speed)
    }

    fun setHardwareMode(mode: HardwareMode) {
        if (!initialized) return
        currentMode = mode
        MPVLib.setPropertyString("hwdec", mode.option)
        Log.i(TAG, "libmpv hwdec mode changed to $mode; active=${MPVLib.getPropertyString("hwdec-current")}")
    }

    fun currentHardwareDecoder(): String =
        if (initialized) MPVLib.getPropertyString("hwdec-current") ?: "no" else "not-initialized"

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!initialized || destroyed.get()) return
        MPVLib.attachSurface(holder.surface)
        surfaceAttached = true
        setOption("force-window", "yes")
        pendingUrl?.let { url ->
            MPVLib.command(arrayOf("loadfile", url, "replace"))
            pendingUrl = null
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (initialized) MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!initialized || !surfaceAttached) return
        runCatching { MPVLib.setPropertyString("vo", "null") }
        runCatching { setOption("force-window", "no") }
        runCatching { MPVLib.detachSurface() }
        surfaceAttached = false
    }

    override fun eventProperty(property: String) {
        if (property == "hwdec-current") {
            post { listener?.onDecoder(currentHardwareDecoder()) }
        }
    }

    override fun eventProperty(property: String, value: Long) = Unit
    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") post { listener?.onState(if (value) "PAUSED" else "PLAYING") }
    }
    override fun eventProperty(property: String, value: String) {
        if (property == "hwdec-current") post { listener?.onDecoder(value) }
    }
    override fun eventProperty(property: String, value: Double) {
        if (property == "time-pos" || property == "duration") {
            val position = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
            post { listener?.onPosition(position, duration) }
        }
    }

    override fun event(eventId: Int) {
        val state = when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                val start = pendingStartPositionSeconds
                if (start > 0.0) MPVLib.command(arrayOf("seek", start.toString(), "absolute+exact"))
                MPVLib.setPropertyBoolean("pause", !startAutoplay)
                if (startAutoplay) "PLAYING" else "PAUSED"
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> "PLAYING"
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> "ENDED"
            MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> "SHUTDOWN"
            else -> return
        }
        post { listener?.onState(state) }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN) {
            post { listener?.onLog(level, "$prefix: ${text.trim()}") }
        }
    }

    override fun close() {
        if (!destroyed.compareAndSet(false, true)) return
        holder.removeCallback(this)
        if (initialized) {
            MPVLib.removeObserver(this)
            MPVLib.removeLogObserver(this)
            if (surfaceAttached) {
                runCatching { MPVLib.setPropertyString("vo", "null") }
                runCatching { MPVLib.setOptionString("force-window", "no") }
                runCatching { MPVLib.detachSurface() }
                surfaceAttached = false
            }
            runCatching { MPVLib.destroy() }
            initialized = false
        }
        bridge?.close()
        bridge = null
        pendingUrl = null
        engineClaimed.set(false)
        Log.i(TAG, "libmpv view released")
    }

    companion object {
        private const val TAG = "HongguoTV-libmpv"
        private val engineClaimed = AtomicBoolean(false)
    }
}
