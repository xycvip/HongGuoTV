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

    internal fun MainActivity.showGenrePicker() {
        if (tab != 0 && tab != 2) return
        val pickerTab = tab
        val anchor = genreButton ?: return
        var selectedType = library.contentType
        var requestId = 0
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        content.addView(TvDialog.title(this, "选择题材"))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val grid = GridLayout(this).apply { columnCount = 3; alignmentMode = GridLayout.ALIGN_BOUNDS; useDefaultMargins = false }
        val loading = text("正在读取题材…", 13f, muted)
        body.addView(typeRow)
        body.addView(loading, lp(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        body.addView(grid)
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(body, ViewGroup.LayoutParams(-1, -2))
        }
        content.addView(scroll, lp(-1, 0).apply { weight = 1f })
        content.layoutParams = LinearLayout.LayoutParams(
            -1, (resources.displayMetrics.heightPixels * 0.72f).toInt()
        )
        lateinit var dialog: AlertDialog
        lateinit var close: View
        fun loadGenres(type: ContentType) {
            selectedType = type
            val ticket = ++requestId
            typeRow.removeAllViews()
            val dialogWidth=(resources.displayMetrics.widthPixels*0.72f).toInt().coerceAtLeast(dp(240))
            val typeGap=(dialogWidth*0.018f).toInt().coerceIn(dp(4),dp(12))
            val typeButtons=mutableListOf<View>()
            listOf(ContentType.SHORT, ContentType.COMIC, ContentType.AI).forEachIndexed { typeIndex,item ->
                val typeButton=button(item.label,item==type) {
                    if(library.contentType!=item) {
                        library.contentType=item
                        genre=null
                        page=1
                        catalogFocus=""
                    }
                    loadGenres(item)
                }.apply {
                    textSize=14f
                    setPadding(dp(4),dp(10),dp(4),dp(10))
                    nextFocusLeftId=typeButtons.lastOrNull()?.id ?: id
                    nextFocusRightId=id
                }
                typeButtons.lastOrNull()?.nextFocusRightId=typeButton.id
                typeButtons+=typeButton
                typeRow.addView(typeButton,LinearLayout.LayoutParams(0,dp(42),1f).apply {
                    leftMargin=typeGap/2
                    rightMargin=typeGap/2
                })
            }
            grid.removeAllViews()
            loading.text = "正在读取${type.label}题材…"
            io.execute {
                val result = runCatching { repository.genres(type) }
                main.post {
                    if (isDestroyed || !dialog.isShowing || ticket != requestId || screen != "catalog" || tab != pickerTab) return@post
                    result.onSuccess { genres ->
                        loading.text = if (genres.isEmpty()) "暂无可选题材" else "选择${type.label}题材"
                        val labels = listOf("全部题材") + genres.map { it.name }
                        labels.forEachIndexed { index, name ->
                            val chosen = if (type == library.contentType && type == selectedType) (if (index == 0) genre == null else genre?.name == name) else index == 0
                            val item = button(name, chosen) {
                                library.contentType = type
                                genre = if (index == 0) null else genres[index - 1]
                                tab = 0
                                page = 1; catalogFocus = ""; tabState.remove(0); tabFocus.remove(0)
                                dialog.dismiss()
                                showCatalog(true, focusType = true)
                            }
                            grid.addView(item, GridLayout.LayoutParams(GridLayout.spec(index / 3), GridLayout.spec(index % 3, 1f)).apply {
                                width = 0
                                setMargins(dp(4), dp(4), dp(4), dp(4))
                            })
                        }
                        val genreCount=grid.childCount
                        for(index in 0 until genreCount) {
                            val item=grid.getChildAt(index)
                            val column=index%3
                            val rowIndex=index/3
                            item.nextFocusLeftId=if(column>0) grid.getChildAt(index-1).id else typeButtons.firstOrNull()?.id ?: item.id
                            item.nextFocusRightId=if(column<2 && index+1<genreCount) grid.getChildAt(index+1).id else item.id
                            item.nextFocusUpId=if(index>=3) grid.getChildAt(index-3).id else typeButtons.getOrNull(column)?.id ?: typeButtons.firstOrNull()?.id ?: item.id
                            item.nextFocusDownId=if(index+3<genreCount) grid.getChildAt(index+3).id else close.id
                        }
                        typeButtons.forEachIndexed { index,button ->
                            button.nextFocusLeftId=typeButtons.getOrNull(index-1)?.id ?: button.id
                            button.nextFocusRightId=typeButtons.getOrNull(index+1)?.id ?: button.id
                            button.nextFocusDownId=grid.getChildAt(index.coerceAtMost((genreCount-1).coerceAtLeast(0)))?.id ?: close.id
                            button.nextFocusUpId=button.id
                        }
                        close.nextFocusUpId=grid.getChildAt(genreCount-1)?.id ?: typeButtons.lastOrNull()?.id ?: close.id
                        close.nextFocusLeftId=close.id
                        close.nextFocusRightId=close.id
                        close.nextFocusDownId=close.id
                        grid.getChildAt(0)?.requestFocus()
                    }.onFailure {
                        loading.text = "题材加载失败，请稍后重试"
                    }
                }
            }
        }
        val buttons = LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        close = TvDialog.capsuleButton(this, "关闭") { dialog.dismiss() }
        buttons.addView(close, lp(-2, -2))
        content.addView(buttons, lp(-1, -2))
        dialog = AlertDialog.Builder(this).setView(TvDialog.card(this, content)).create()
        TvDialog.styleWindow(this, dialog, widthFraction = 0.55f, maxWidthDp = 620)
        dialog.setOnDismissListener { if (anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.55f).toInt().coerceAtMost(dp(620)),
            (resources.displayMetrics.heightPixels * 0.72f).toInt()
        )
        dialog.window?.decorView?.post {
            if(dialog.isShowing) typeRow.getChildAt(0)?.requestFocus()
        }
        loadGenres(selectedType)
    }
