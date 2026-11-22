// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.hongguotv.core.DanmakuEmoji
import com.hongguotv.core.OfficialComment
import com.hongguotv.core.OfficialCommentPage

/**
 * 播放页右侧的官方评论面板。
 *
 * 改造前它是"一列同样大小的灰按钮"：上一集/下一集/关闭和每条评论的点赞/回复长得一样，
 * 标题和正文字号只差 3sp，一屏十几块同色矩形，视线没有落点。
 *
 * 现在分三层：标题区（剧名 + 条数，操作退到右上角的小号 ghost）、
 * 评论列表（头像 + 昵称 + 时间 + 正文 + 一行操作）、回复用左侧细线缩进。
 * 颜色全部走语义令牌，浅色主题下不再是"播放器上贴了一块深色板"。
 */
internal class OfficialCommentPanel(
    context: Context,
    private val signedIn: Boolean,
    private val listener: Listener,
    private val avatarLoader: (ImageView, String) -> Unit = { _, _ -> },
) : FrameLayout(context) {
    interface Listener {
        fun close()
        fun previousEpisode()
        fun nextEpisode()
        fun loadMore()
        fun like(comment: OfficialComment)
        fun reply(comment: OfficialComment)
        fun openReplies(comment: OfficialComment)
        fun loadMoreReplies(comment: OfficialComment)
        fun send(text: String)
        fun login()
    }

    private val backgroundColor = TvStyle.colors.background
    private val surfaceColor = TvStyle.colors.surface
    private val textColor = TvStyle.colors.textPrimary
    private val mutedColor = TvStyle.colors.textSecondary
    private val accentColor = TvStyle.colors.accent

    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val countLabel = label("评论", TvMetric.TEXT_SUBTITLE, true)
    private val episodeLabel = label("", TvMetric.TEXT_CAPTION, false, mutedColor)
    private val statusLabel = label("", TvMetric.TEXT_CAPTION, false, mutedColor).apply { isFocusable = true }
    private val composerLabel = label("", TvMetric.TEXT_CAPTION, false, mutedColor)
    private val input = EditText(context).apply {
        setTextColor(textColor)
        setHintTextColor(mutedColor)
        textSize = TvMetric.TEXT_BODY
        hint = "说说你的看法……"
        minLines = 1
        maxLines = 3
        isSingleLine = false
        imeOptions = EditorInfo.IME_ACTION_SEND
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = TvStyle.shape(context, surfaceColor, TvStyle.colors.strokeSubtle, TvMetric.RADIUS_M)
        isFocusable = true
    }
    private val sendButton = action("发送", primary = true) { listener.send(input.text.toString()) }
    private var canSend = signedIn
    private var loading = false
    private var hasMore = false
    private var currentItems: List<OfficialComment> = emptyList()
    private var currentReplies: Map<String, List<OfficialComment>> = emptyMap()
    private val replyCursors = mutableMapOf<String, String>()
    private val replyHasMore = mutableMapOf<String, Boolean>()
    private val replyLoading = mutableSetOf<String>()
    private var replyTarget: OfficialComment? = null
    private var draft = ""
    /** 连续异步重绘期间暂存焦点，避免展开回复时焦点回到列表顶部。 */
    private var pendingFocusTag: String? = null

    init {
        setBackgroundColor(backgroundColor)
        isFocusable = false
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
        }

        // ── 标题区：左侧两级文字（标题 + 哪一集），右侧三个小号操作。
        // 操作不再和标题同一字号，避免"关闭"看起来和剧名同等重要。
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(countLabel, LinearLayout.LayoutParams(-1, -2))
        titles.addView(episodeLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        val headerActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerActions.addView(action("上一集") { listener.previousEpisode() }, marginStart(6))
        headerActions.addView(action("下一集") { listener.nextEpisode() }, marginStart(6))
        headerActions.addView(action("关闭") { listener.close() }, marginStart(6))
        header.addView(headerActions, LinearLayout.LayoutParams(-2, -2))
        page.addView(header, LinearLayout.LayoutParams(-1, -2))
        page.addView(statusLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        page.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(6)
            bottomMargin = dp(10)
        })
        page.addView(composerLabel)
        if (signedIn) {
            val composer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            composer.addView(input, LinearLayout.LayoutParams(0, dp(72), 1f))
            composer.addView(sendButton, LinearLayout.LayoutParams(dp(80), dp(44)).apply {
                leftMargin = dp(TvMetric.GAP_S)
            })
            page.addView(composer)
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    listener.send(input.text.toString())
                    true
                } else false
            }
        } else {
            page.addView(
                action("登录红果账号后发表评论、回复和点赞") { listener.login() }.apply {
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) },
            )
        }
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showLoading()
    }

    private fun marginStart(value: Int) = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(value) }

    fun setEpisode(title: String, episodeNumber: Int) {
        countLabel.text = "评论"
        episodeLabel.text = "《$title》· 第 $episodeNumber 集"
        currentItems = emptyList()
        currentReplies = emptyMap()
        replyCursors.clear()
        replyHasMore.clear()
        replyLoading.clear()
        pendingFocusTag = null
        hasMore = false
        clearDraft()
        setSending(false)
        input.hint = "说说你的看法……"
        statusLabel.text = "正在加载评论…"
        render()
    }

    fun setPage(page: OfficialCommentPage, append: Boolean, replies: Map<String, List<OfficialComment>> = currentReplies) {
        loading = false
        val existingIds = if (append) currentItems.mapTo(mutableSetOf()) { it.id } else emptySet()
        val newlyLoaded = if (append) page.items.filterNot { it.id in existingIds } else emptyList()
        currentItems = (if (append) currentItems + page.items else page.items).distinctBy { it.id }
        currentReplies = replies
        hasMore = page.hasMore
        statusLabel.text = if (currentItems.isEmpty()) "暂无评论" else "共 ${page.total} 条评论"
        // 加载下一页后，从新增评论的第一个操作项继续向下浏览，而不是停在列表末尾的加载按钮。
        if (newlyLoaded.isNotEmpty()) pendingFocusTag = "comment:${newlyLoaded.first().id}:like"
        render()
    }

    fun setReplies(commentId: String, page: OfficialCommentPage, append: Boolean = false) {
        val combined = if (append) currentReplies[commentId].orEmpty() + page.items else page.items
        currentReplies = currentReplies + (commentId to combined.distinctBy { it.id })
        replyCursors[commentId] = page.nextCursor
        replyHasMore[commentId] = page.hasMore
        replyLoading.remove(commentId)
        render()
    }

    fun setReplyLoading(commentId: String, loading: Boolean) {
        if (loading) replyLoading.add(commentId) else replyLoading.remove(commentId)
        render()
    }

    fun showLoading(message: String = "正在加载评论…") {
        loading = true
        statusLabel.text = message
        render()
    }

    fun showError(message: String) {
        loading = false
        statusLabel.text = message
        render()
    }

    fun setSending(sending: Boolean) {
        sendButton.isEnabled = !sending
        sendButton.alpha = if (sending) .55f else 1f
        sendButton.text = if (sending) "发送中" else "发送"
    }

    fun keepDraft(value: String) {
        draft = value
        if (signedIn && input.text.toString() != value) input.setText(value)
    }

    fun clearDraft() {
        draft = ""
        if (signedIn) input.setText("")
        replyTarget = null
        composerLabel.text = ""
        input.hint = "说说你的看法……"
    }

    fun setReplyTarget(comment: OfficialComment) {
        replyTarget = comment
        composerLabel.text = "回复 @${comment.author}（按返回可关闭评论面板）"
        if (signedIn) {
            input.hint = "回复 @${comment.author}……"
            input.requestFocus()
        }
    }

    fun replyTarget(): OfficialComment? = replyTarget
    fun draft(): String = if (signedIn) input.text.toString() else draft

    /** Let the first Back dismiss the TV keyboard before the Activity closes this panel. */
    fun dismissEditorIfNeeded(): Boolean {
        if (!signedIn || !input.hasFocus()) return false
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return false
        if (!manager.isAcceptingText) return false
        manager.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        return true
    }

    fun focusCommentArea() {
        val target = list.getChildAt(0)
        target?.let { firstFocusable(it)?.requestFocus() }
            ?: statusLabel.requestFocus()
    }

    private fun firstFocusable(view: View): View? {
        if (view.isFocusable && view.visibility == View.VISIBLE) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) firstFocusable(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun render() {
        val focusedInList = list.findFocus()
        val focusOutsideList = focusedInList == null && findFocus() != null
        val focusTag = if (focusOutsideList) null else pendingFocusTag ?: (focusedInList?.tag as? String)
        if (focusOutsideList) pendingFocusTag = null else pendingFocusTag = focusTag
        list.removeAllViews()
        if (currentItems.isEmpty() && !loading && statusLabel.text.isNullOrBlank()) {
            statusLabel.text = "暂无评论"
        }
        currentItems.forEach { comment ->
            list.addView(commentCard(comment, false))
            currentReplies[comment.id]?.forEach { reply ->
                list.addView(commentCard(reply, true))
            }
            if (replyHasMore[comment.id] == true) {
                list.addView(action(
                    if (comment.id in replyLoading) "正在加载回复…" else "查看更多回复",
                    "more-replies:${comment.id}"
                ) {
                    if (comment.id !in replyLoading) listener.loadMoreReplies(comment)
                }, LinearLayout.LayoutParams(-1, dp(36)).apply {
                    leftMargin = dp(44)
                    topMargin = dp(2)
                })
            }
            list.addView(View(context).apply {
                setBackgroundColor(TvStyle.colors.strokeSubtle)
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(10)
                bottomMargin = dp(2)
            })
        }
        if (hasMore) {
            list.addView(action(if (loading) "加载中…" else "加载更多评论", "more-comments") {
                if (!loading) listener.loadMore()
            }, LinearLayout.LayoutParams(-1, dp(40)))
        }
        if (currentItems.isEmpty() && !loading) {
            list.addView(label("还没有人发表本集评论", TvMetric.TEXT_BODY, false, mutedColor).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(32), dp(8), dp(32))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        list.post {
            val target = focusTag?.let { list.findViewWithTag<View>(it) }
            if (target != null) target.requestFocus()
            else if (!focusOutsideList) firstFocusable(list)?.requestFocus()
            if (pendingFocusTag == focusTag) pendingFocusTag = null
        }
    }

    private fun commentCard(comment: OfficialComment, isReply: Boolean): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(if (isReply) 6 else 12), dp(6), dp(4))
            background = rounded(Color.TRANSPARENT, TvMetric.RADIUS_S)
            isFocusable = isReply
            isClickable = isReply
            tag = if (isReply) "reply:${comment.id}" else "comment:${comment.id}"
            if (isReply) {
                setOnFocusChangeListener { view, focused ->
                    view.background = TvStyle.shape(
                        context,
                        if (focused) TvStyle.colors.surfaceRaised else Color.TRANSPARENT,
                        if (focused) TvStyle.colors.focusRing else Color.TRANSPARENT,
                        TvMetric.RADIUS_S,
                    )
                }
                setOnClickListener { listener.reply(comment) }
            }
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (isReply) setPadding(dp(44), 0, 0, 0)
        }
        val authorLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(surfaceColor, 16)
            contentDescription = "${comment.author}头像"
        }
        if (comment.avatar.startsWith("https://")) avatarLoader(avatar, comment.avatar)
        authorLine.addView(avatar, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(9) })
        authorLine.addView(
            label(comment.author, TvMetric.TEXT_BODY, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (comment.createdAt > 0) {
            authorLine.addView(label(timeText(comment.createdAt), TvMetric.TEXT_CAPTION, false, mutedColor))
        }
        body.addView(authorLine)
        body.addView(label(DanmakuEmoji.display(comment.text), TvMetric.TEXT_BODY, false, textColor).apply {
            setPadding(0, dp(6), 0, dp(6))
            setLineSpacing(0f, 1.35f)
        })
        body.addView(commentActions(comment, isReply))

        if (isReply) {
            // 回复用一根 2dp 竖线表示层级：只靠缩进时，深色背景下几乎看不出从属关系。
            // 竖线放在 FrameLayout 里才能可靠地 match_parent 到正文高度。
            val frame = FrameLayout(context)
            frame.addView(
                View(context).apply { setBackgroundColor(TvStyle.colors.strokeSubtle) },
                FrameLayout.LayoutParams(dp(2), FrameLayout.LayoutParams.MATCH_PARENT),
            )
            frame.addView(body, FrameLayout.LayoutParams(-1, -2))
            card.addView(frame, LinearLayout.LayoutParams(-1, -2))
        } else {
            card.addView(body, LinearLayout.LayoutParams(-1, -2))
        }
        return card
    }

    /** 每条评论的操作行：点赞/回复是次要动作，用纯文字；数量以 caption 呈现。 */
    private fun commentActions(comment: OfficialComment, isReply: Boolean): View {
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (isReply) {
            actions.addView(label("选择此回复可继续回复", TvMetric.TEXT_CAPTION, false, mutedColor))
            return actions
        }
        actions.addView(action(if (comment.liked) "♥ 已赞 ${comment.likes}" else "♡ ${comment.likes}", "comment:${comment.id}:like") {
            listener.like(comment)
        })
        actions.addView(action("回复", "comment:${comment.id}:reply") { listener.reply(comment) }, marginStart(4))
        if (comment.replies > 0) {
            actions.addView(action("查看 ${comment.replies} 条回复", "comment:${comment.id}:open-replies") {
                listener.openReplies(comment)
            }, marginStart(4))
        }
        return actions
    }

    private fun label(value: String, size: Float, bold: Boolean, color: Int = textColor) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            isFocusable = false
        }

    private fun action(value: String, focusTag: String? = null, primary: Boolean = false, click: () -> Unit) =
        TextView(context).apply {
            text = value
            tag = focusTag
            textSize = TvMetric.TEXT_BODY
            setTextColor(if (primary) TvStyle.colors.onAccent else textColor)
            gravity = Gravity.CENTER
            setPadding(dp(if (primary) 14 else 10), dp(6), dp(if (primary) 14 else 10), dp(6))
            minHeight = dp(34)
            isFocusable = true
            isClickable = true
            fun paint(focused: Boolean) {
                background = when {
                    primary -> TvStyle.shape(
                        context,
                        if (focused) TvStyle.colors.accentPressed else accentColor,
                        if (focused) TvStyle.colors.focusRing else Color.TRANSPARENT,
                        TvMetric.RADIUS_M,
                    )
                    focused -> TvStyle.shape(
                        context,
                        withAlpha(textColor, .12f),
                        TvStyle.colors.focusRing,
                        TvMetric.RADIUS_S,
                    )
                    else -> TvStyle.shape(context, Color.TRANSPARENT, Color.TRANSPARENT, TvMetric.RADIUS_S)
                }
                setTextColor(
                    when {
                        primary -> TvStyle.colors.onAccent
                        focused -> TvStyle.colors.textPrimary
                        else -> mutedColor
                    },
                )
            }
            paint(false)
            setOnFocusChangeListener { _, focused -> paint(focused) }
            setOnClickListener { click() }
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun timeText(seconds: Long): String {
        val delta = (System.currentTimeMillis() / 1000 - seconds).coerceAtLeast(0)
        return when {
            delta < 60 -> "刚刚"
            delta < 3600 -> "${delta / 60}分钟前"
            delta < 86400 -> "${delta / 3600}小时前"
            delta < 86400L * 30 -> "${delta / 86400}天前"
            else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(seconds * 1000))
        }
    }
}
