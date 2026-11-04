// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** 未登录账户页：独立负责二维码展示、刷新焦点与首轮自动取码。 */
internal fun MainActivity.renderAccountLoginCard(
    host: FrameLayout,
    mobileLayout: Boolean,
    availableWidthDp: Float,
    availableHeightDp: Float,
) {
            // QR 尺寸同时受实际可用宽度和高度约束；短屏时优先缩小二维码，不让卡片被屏幕裁切。
            val cardWidthDp = minOf(
                (availableWidthDp - 20f).coerceAtLeast(180f),
                if (mobileLayout) 380f else 460f
            )
            val cardWidth = dp(cardWidthDp.toInt())
            val card = column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = rounded(Color.rgb(36, 42, 51), Color.rgb(48, 57, 70))
                setPadding(dp(if (mobileLayout) 14 else 26), dp(if (mobileLayout) 14 else 24),
                    dp(if (mobileLayout) 14 else 26), dp(if (mobileLayout) 14 else 24))
                elevation = dp(6).toFloat()
            }
            val loginHeading = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text("登录红果账户", if (mobileLayout) 21f else 26f, white).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER_VERTICAL
                }, lp(0, -2).apply { weight = 1f })
            }
            card.addView(loginHeading, lp(-1, -2))
            card.addView(text("使用手机红果 App 扫码，安全同步收藏与观看记录", if (mobileLayout) 12f else 14f, muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(if (mobileLayout) 10 else 16))
            }, lp(-1, -2))
            val qr = cloudAccount.inlineQrBitmap
            val horizontalPaddingDp = if (mobileLayout) 12f else 26f
            val qrWidthLimitDp = (cardWidthDp - horizontalPaddingDp * 2f - 12f).coerceAtLeast(88f)
            // 为标题、说明、状态和按钮预留空间，避免二维码在手机竖屏/短屏上顶出可视区域。
            val qrHeightLimitDp = (availableHeightDp - if (mobileLayout) 208f else 230f).coerceAtLeast(88f)
            val qrSize = dp(minOf(
                if (mobileLayout) 176 else 240,
                qrWidthLimitDp.toInt(),
                qrHeightLimitDp.toInt()
            ).coerceAtLeast(88))
            val qrFrame = FrameLayout(this)
            val qrImage = ImageView(this).apply {
                setImageBitmap(qr)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.WHITE)
                setPadding(dp(5), dp(5), dp(5), dp(5))
                contentDescription = "红果账户登录二维码"
                visibility = if (qr != null) View.VISIBLE else View.GONE
            }
            val placeholder = FrameLayout(this).apply {
                background = rounded(Color.WHITE)
                addView(text(if (cloudAccount.inlineBusy) "正在获取二维码…" else "二维码暂不可用", 13f, Color.DKGRAY).apply {
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(-1, -1))
                visibility = if (qr == null) View.VISIBLE else View.GONE
            }
            qrFrame.addView(qrImage, FrameLayout.LayoutParams(-1, -1))
            qrFrame.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
            accountQrImageView = qrImage
            accountQrPlaceholderView = placeholder
            card.addView(qrFrame, lp(qrSize, qrSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(if (mobileLayout) 8 else 12)
            })
            val qrStatus = text(cloudAccount.inlineStatus, if (mobileLayout) 11f else 13f, muted).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(0, 0, 0, dp(if (mobileLayout) 8 else 12))
            }
            accountQrStatusView = qrStatus
            card.addView(qrStatus, lp(-1, -2))
            val refresh = button(if (cloudAccount.inlineBusy) "正在获取…" else "刷新二维码") {
                if (!cloudAccount.inlineBusy) {
                    accountQrAutoStarted = true
                    cloudAccount.startQrInline()
                }
            }.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                val accountNavigation = navForTab()
                nextFocusUpId = accountNavigation?.id ?: View.NO_ID
                nextFocusDownId = id
                nextFocusLeftId = id
                nextFocusRightId = id
                accountNavigation?.nextFocusDownId = id
            }
            accountQrRefreshView = refresh
            card.addView(refresh, lp(-2, dp(if (mobileLayout) 38 else 44)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            // 部分电视 ROM 不会自动把焦点落到滚动容器中的唯一操作按钮，显式恢复焦点链。
            refresh.post { if (refresh.isAttachedToWindow) refresh.requestFocus() }
            val loginHost = FrameLayout(this).apply {
                minimumHeight = host.height.coerceAtLeast(dp(240))
            }
            loginHost.addView(card, FrameLayout.LayoutParams(cardWidth, -2, Gravity.CENTER))
            val loginScroll = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                clipToPadding = false
            }
            loginScroll.setOnScrollChangeListener { _, _, scrollY, _, oldY ->
                onCatalogContentScroll(scrollY>dp(1))
            }
            loginScroll.addView(loginHost, ViewGroup.LayoutParams(-1, -2))
            host.addView(loginScroll, FrameLayout.LayoutParams(-1, -1))
            if (cloudAccount.inlineQrBitmap == null && !cloudAccount.inlineBusy && !accountQrAutoStarted) {
                accountQrAutoStarted = true
                host.post { if (tab == 6 && !cloudAccount.signedIn && cloudAccount.inlineQrBitmap == null && !cloudAccount.inlineBusy) cloudAccount.startQrInline() }
            }
            return
}

/** 已登录账户页的接口在线状态小组件。 */
internal fun MainActivity.accountStatusIndicator(): LinearLayout = row().apply {
    gravity = Gravity.CENTER
    background = rounded(TvStyle.surface)
    setPadding(dp(9), dp(6), dp(9), dp(6))
    isFocusable = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    val dot = text("●", 10f, Color.rgb(70, 150, 255)).apply {
        setPadding(0, 0, dp(5), 0)
    }
    val label = text(
        when (accountApiOnline) {
            true -> "已上线"
            false -> "已下线"
            null -> "检测中"
        },
        12f,
        white
    )
    accountApiDotView = dot
    accountApiLabelView = label
    addView(dot, lp(-2, -2))
    addView(label, lp(-2, -2))
}
