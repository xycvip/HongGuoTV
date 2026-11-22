// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

    internal fun MainActivity.showAccountPage() { debugLog("页面导航：打开红果账户"); switchTab(6) }

    internal fun MainActivity.renderAccountContent(host: FrameLayout) {
        if (!runCatching { cloudAccount }.isSuccess || tab != 6 || screen != "catalog") return

        // 账户页必须先完成渲染；容器尚未测量时用屏幕可用宽度兜底，
        // 避免布局回调未触发时二维码页和登录后页面同时空白。
        val renderTicket = ++accountRenderRevision
        val previousFocus = host.findFocus()?.tag?.toString()
        val previousScrollY = (host.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        // Replacing the account section removes the currently focused tab/card. Keep focus
        // on the account host during that brief rebuild so Android cannot fall back to Home.
        host.isFocusable = true
        host.isFocusableInTouchMode = true
        host.requestFocus()
        host.removeAllViews()
        accountQrImageView = null
        accountQrPlaceholderView = null
        accountQrStatusView = null
        accountQrRefreshView = null
        accountApiDotView = null
        accountApiLabelView = null
        if (!cloudAccount.signedIn) {
            accountApiCheckStarted = false
            accountApiOnline = null
        }
        val mobileLayout = widthDp() < 600f
        val density = resources.displayMetrics.density
        // 优先使用账户容器实测宽度；首帧尚未测量时按屏幕可用宽度估算。
        val availableWidthDp = (host.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(40))).toFloat() / density
        val availableHeightDp = (host.height.takeIf { it > 0 } ?: (resources.displayMetrics.heightPixels - dp(130))).toFloat() / density

        // 登录后显示真实账号接口连通状态，而不是仅凭本地登录凭据判断在线。
        if (!cloudAccount.signedIn) {
            renderAccountLoginCard(host, mobileLayout, availableWidthDp, availableHeightDp)
            return
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = false
        }
        scroll.setOnScrollChangeListener { _, _, scrollY, _, oldY ->
            onCatalogContentScroll(scrollY>dp(1))
        }
        val page = column().apply {
            setPadding(dp(if (mobileLayout) 12 else 24), dp(if (mobileLayout) 14 else 20),
                dp(if (mobileLayout) 12 else 24), dp(24))
        }
        val profileRow = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val avatarSize = dp(if (mobileLayout) 48 else 60)
        // 默认保留昵称首字作为兜底；头像 URL 加载成功后覆盖显示真实头像。
        val avatarFallback = TextView(this).apply {
            text = cloudAccount.accountLabel.take(1).ifBlank { "红" }
            textSize = if (mobileLayout) 21f else 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(TvStyle.accent)
            }
        }
        val avatar = FrameLayout(this).apply {
            clipToOutline = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(TvStyle.accent)
            }
            addView(avatarFallback, FrameLayout.LayoutParams(-1, -1))
            val image = ImageView(this@renderAccountContent).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
                contentDescription = "红果账户头像"
            }
            addView(image, FrameLayout.LayoutParams(-1, -1))
            if (cloudAccount.accountAvatarUrl.isNotBlank()) {
                val revision = cloudAccount.accountProfileRevision
                if (revision != renderedAccountAvatarRevision) {
                    renderedAccountAvatarRevision = revision
                    artwork.reload(image, cloudAccount.accountAvatarUrl)
                } else {
                    loadCover(image, cloudAccount.accountAvatarUrl)
                }
            }
        }
        profileRow.addView(avatar, lp(avatarSize, avatarSize).apply { rightMargin = dp(12) })
        val identity = column()
        identity.addView(text(cloudAccount.accountLabel, if (mobileLayout) 20f else 25f, white).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp(-1, -2))
        identity.addView(text("红果账户 · ${cloudAccount.historyStatus}", 12f, muted).apply {
            setPadding(0, dp(5), 0, 0)
            maxLines = 2
        }, lp(-1, -2))
        profileRow.addView(identity, lp(0, -2).apply { weight = 1f })
        // Keep the API connectivity indicator aligned at the far right of the nickname row.
        profileRow.addView(accountStatusIndicator(), lp(-2, -2).apply { leftMargin = dp(8) })
        page.addView(profileRow, lp(-1, -2))
        if (!accountApiCheckStarted) {
            accountApiCheckStarted = true
            accountApiOnline = null
            cloudAccount.checkConnection { connected, _ ->
                if (isDestroyed || !cloudAccount.signedIn) return@checkConnection
                accountApiOnline = connected
                accountApiDotView?.setTextColor(
                    if (connected) Color.rgb(76, 205, 125) else Color.rgb(235, 75, 75)
                )
                accountApiLabelView?.text = if (connected) "已上线" else "已下线"
            }
        }

        val actions = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(14)) }
        addButton(actions, "刷新云端数据") {
            cloudAccount.refreshHistory()
            cloudAccount.refreshFavorites()
            cloudAccount.refreshReservations()
            Toast.makeText(this, "正在刷新云端数据…", Toast.LENGTH_SHORT).show()
        }.apply { tag = "account:refresh" }
        addButton(actions, "退出登录") {
            accountSection = 0
            accountQrAutoStarted = false
            cloudAccount.logoutInline()
        }.apply { tag = "account:logout" }
        page.addView(actions, lp(-1, -2))

        val tabs = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val sections = listOf("观看历史", "账户收藏", "预约·已上线", "预约·待上线")
        sections.forEachIndexed { index, label ->
            val item = addButton(tabs, label, accountSection == index) {
                accountSection = index
                if (index == 2 || index == 3) cloudAccount.refreshReservations()
                renderAccountContent(host)
            }
            item.tag = "account:tab:$index"
            item.setTextColor(if (accountSection == index) accent else muted)
        }
        page.addView(tabs, lp(-1, -2))
        page.addView(View(this).apply { setBackgroundColor(TvStyle.outline) }, lp(-1, dp(1)).apply { bottomMargin = dp(12) })
        var firstAccountArtworkCard: View? = null

        fun sectionHeading(title: String, subtitle: String) {
            page.addView(text(title, if (mobileLayout) 18f else 21f, white).apply {
                typeface = Typeface.DEFAULT_BOLD
            }, lp(-1, -2))
            page.addView(text(subtitle, 12f, muted).apply {
                setPadding(0, dp(5), 0, dp(10))
            }, lp(-1, -2))
        }
        val artworkShelfRenderer = AccountArtworkShelfRenderer(
            activity = this,
            page = page,
            tabs = tabs,
            accountSection = accountSection,
            columns = gridColumns,
            dp = ::dp,
            column = ::column,
            text = { value, size, color -> text(value, size, color) },
            layoutParams = { width, height -> lp(width, height) },
            rounded = ::rounded,
            loadCover = ::loadCover,
        )
        fun addArtworkShelf(entries: List<Triple<Series, String, () -> Unit>>, shelfKey: String) {
            artworkShelfRenderer.render(entries, shelfKey)?.let {
                if (firstAccountArtworkCard == null) firstAccountArtworkCard = it
            }
        }

        when (accountSection) {
            0 -> {
                val history = cloudAccount.historyRows()
                sectionHeading("观看历史", if (cloudAccount.historyLoading) "正在同步云端记录…" else "最近观看 · ${history.size} 部")
                if (history.isEmpty()) {
                    page.addView(text(if (cloudAccount.historyLoading) "正在读取观看记录…" else "暂无云端观看记录", 14f, muted).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(30), 0, dp(30))
                    }, lp(-1, -2))
                } else addArtworkShelf(history.map { item ->
                    // Keep the same compact episode + elapsed-time label as Home's
                    // "最近观看" shelf; omit total episode count and duration denominator.
                    val progressText = "第 ${item.episodeIndex + 1} 集 · ${formatTime(item.position)}"
                    Triple(item.series, progressText) {
                        pendingCloudProgress = item
                        openDetail(item.series, true)
                    }
                }, "account:history")
            }
            1 -> {
                val fav = cloudAccount.favoriteState()
                sectionHeading("账户收藏", if (fav.loading) "正在读取收藏…" else "已同步 ${fav.rows.size} 部作品")
                if (!fav.loaded && !fav.loading) cloudAccount.refreshFavorites()
                if (fav.rows.isEmpty()) {
                    page.addView(text(if (fav.loading) "正在读取账户收藏…" else fav.message, 14f, muted).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(30), 0, dp(30))
                    }, lp(-1, -2))
                } else addArtworkShelf(fav.rows.map { item ->
                    Triple(item.series, "红果账户收藏") { openDetail(item.series, true) }
                }, "account:favorites")
            }
            2, 3 -> {
                val online = accountSection == 2
                val state = cloudAccount.reservationState(online)
                sectionHeading(if (online) "预约·已上线" else "预约·待上线", if (state.loading) "正在同步预约记录…" else state.message)
                if (!state.loaded && !state.loading) cloudAccount.refreshReservations()
                if (state.rows.isEmpty()) {
                    val emptyText = if (state.loading) "正在读取预约记录…" else if (state.loaded) "暂无${if (online) "预约·已上线" else "预约·待上线"}作品" else state.message
                    page.addView(text(emptyText, 14f, muted).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(30), 0, dp(30))
                    }, lp(-1, -2))
                } else addArtworkShelf(state.rows.map { item ->
                    val sub = when {
                        item.publishText.isNotBlank() -> item.publishText
                        item.publishAt > 0L -> if (online) "已上线" else "预约作品 · 等待上线"
                        else -> if (online) "已上线" else "预约成功 · 等待上线"
                    }
                    Triple(item.series, sub) {
                        if (online) openDetail(item.series, true)
                        else Toast.makeText(this, "该作品尚未上线，可在预约列表查看更新。", Toast.LENGTH_SHORT).show()
                    }
                }, if (online) "account:online" else "account:upcoming")
            }
        }

        // 账户页建立明确的上下焦点链：导航 → 操作按钮 → 子栏目 → 当前栏目作品。
        val accountNavigation = navForTab()
        val actionViews = (0 until actions.childCount).map { actions.getChildAt(it) }.filter { it.isFocusable }
        val accountTabs = (0 until tabs.childCount).map { tabs.getChildAt(it) }.filter { it.isFocusable }
        actionViews.forEachIndexed { index, view ->
            view.nextFocusUpId = accountNavigation.id
            view.nextFocusDownId = accountTabs.firstOrNull()?.id ?: firstAccountArtworkCard?.id ?: view.id
            view.nextFocusLeftId = actionViews.getOrNull(index - 1)?.id ?: view.id
            view.nextFocusRightId = actionViews.getOrNull(index + 1)?.id ?: view.id
        }
        accountTabs.forEachIndexed { index, view ->
            view.nextFocusLeftId = accountTabs.getOrNull(index - 1)?.id ?: view.id
            view.nextFocusRightId = accountTabs.getOrNull(index + 1)?.id ?: view.id
            view.nextFocusUpId = actionViews.lastOrNull()?.id ?: accountNavigation.id
            view.nextFocusDownId = firstAccountArtworkCard?.id ?: view.id
        }
        accountNavigation.nextFocusDownId = actionViews.firstOrNull()?.id
            ?: accountTabs.firstOrNull()?.id ?: firstAccountArtworkCard?.id ?: accountNavigation.id
        scroll.addView(page, ViewGroup.LayoutParams(-1, -2))
        host.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        // 页面因云端刷新而重绘时，恢复原按钮/卡片焦点与滚动位置，避免焦点掉回顶部导航。
        // 切换账户子栏目时，焦点应落在新选中的栏目，而不是重建前的旧栏目。
        // 云端数据连续回调可能触发多次重绘，只允许最后一次渲染恢复焦点。
        val restoreTag = when {
            previousFocus?.startsWith("account:tab:") == true -> "account:tab:$accountSection"
            previousFocus?.startsWith("account:") == true -> previousFocus
            else -> "account:tab:$accountSection"
        }
        host.post {
            if (renderTicket != accountRenderRevision || tab != 6 || screen != "catalog" || host.parent == null) return@post
            scroll.post {
                if (renderTicket != accountRenderRevision || tab != 6 || screen != "catalog" || host.parent == null) return@post
                scroll.scrollTo(0, previousScrollY.coerceAtMost((page.height - scroll.height).coerceAtLeast(0)))
                val target = host.findViewWithTag<View>(restoreTag)
                    ?: host.findViewWithTag<View>("account:tab:$accountSection")
                    ?: host.findViewWithTag<View>("account:refresh")
                target?.requestFocus()
            }
        }
    }
