// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 统一弹窗视觉层：深色玻璃卡片底、大圆角、细描边、胶囊按钮、 accent 焦点高亮。
 * 所有原生 AlertDialog 统一走这里，保证全应用弹窗观感一致。
 * 注意：构建方法只创建不显示，由调用方决定何时 show() 与焦点恢复。
 */
object TvDialog {

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    /** 弹窗内容卡片：圆角 + 细描边 + 主题 surface 底色 + 投影浮起。 */
    fun card(activity: Activity, content: View): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(TvStyle.surface)
                cornerRadius = dp(activity, 20).toFloat()
                setStroke(dp(activity, 1), TvStyle.outline)
            }
            // 弹窗在最顶层、无裁剪容器，静态投影安全：一道淡影让卡片浮在页面之上，
            // 而不是和背景贴在同一张纸上。
            elevation = dp(activity, 12).toFloat()
            setPadding(dp(activity, 28), dp(activity, 26), dp(activity, 28), dp(activity, 22))
        }
        card.addView(content)
        return card
    }

    /** 弹窗标题：20sp 加粗，底部留间距。 */
    fun title(activity: Activity, text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 21f
        letterSpacing = 0.01f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(TvStyle.text)
        setPadding(0, 0, 0, dp(activity, 15))
    }

    /** 弹窗正文/说明：15sp，muted 色，行高舒适。 */
    fun body(activity: Activity, text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = TvMetric.TEXT_BODY
        setTextColor(TvStyle.muted)
        setLineSpacing(0f, 1.35f)
        setPadding(0, 0, 0, dp(activity, 14))
    }

    /**
     * 胶囊按钮。
     *
     * 改造前"取消"和"确认"长得一模一样：都是灰底 + 橙色描边，焦点态一起变成橙色实心。
     * 用户没法从外观判断哪个是安全出口、哪个会真的执行。现在分两级：
     *  - [primary] = true 的确认按钮常态就是强调色实心，焦点态加白环；
     *  - 其余按钮常态是浅表面 + 细描边，焦点态抬到 surfaceRaised + 白环。
     * 白环和页面里的按钮、卡片是同一套焦点语言。
     */
    fun capsuleButton(activity: Activity, text: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val dp = { v: Int -> dp(activity, v) }
        return Button(activity).apply {
            this.text = text
            textSize = TvMetric.TEXT_LABEL
            minHeight = dp(TvMetric.ROW_HEIGHT_COMPACT + 2)
            setPadding(dp(22), 0, dp(22), 0)
            fun paint(focused: Boolean) {
                val fill = when {
                    primary -> if (focused) TvStyle.colors.accentPressed else TvStyle.colors.accent
                    focused -> TvStyle.colors.surfaceRaised
                    else -> TvStyle.colors.surface
                }
                val stroke = when {
                    focused -> TvStyle.colors.focusRing
                    primary -> TvStyle.colors.accent
                    else -> TvStyle.colors.strokeSubtle
                }
                // 焦点态走 FocusFrame（柔光+白环，零越界）；主按钮焦点环压在强调底上。
                background = if (focused) {
                    FocusFrame.ring(activity, fill, 24)
                } else {
                    GradientDrawable().apply {
                        setColor(fill)
                        cornerRadius = dp(24).toFloat()
                        setStroke(dp(1), stroke)
                    }
                }
                setTextColor(
                    when {
                        primary -> TvStyle.colors.onAccent
                        focused -> TvStyle.colors.textPrimary
                        else -> TvStyle.colors.textPrimary
                    },
                )
                setTypeface(null, if (focused || primary) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                elevation = 0f
                animate().cancel(); scaleX = 1f; scaleY = 1f
            }
            paint(false)
            setOnFocusChangeListener { _, focused -> paint(focused) }
            setOnClickListener { onClick() }
        }
    }

    /** 普通列表适配器：文字颜色跟随主题，深浅卡片底上均可读（系统布局在暖玉白主题下不可读）。 */
    fun listAdapter(activity: Activity, labels: List<String>): android.widget.ArrayAdapter<String> {
        return object : android.widget.ArrayAdapter<String>(activity, 0, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? TextView) ?: TextView(activity).apply {
                    textSize = TvMetric.TEXT_LABEL
                    setPadding(dp(activity, 18), dp(activity, 14), dp(activity, 18), dp(activity, 14))
                }
                view.text = getItem(position)
                view.setTextColor(TvStyle.text)
                return view
            }
        }
    }

    /** 勾选列表适配器：单选/多选弹窗通用，文字与勾选标记颜色跟随主题。 */
    fun choiceAdapter(activity: Activity, labels: List<String>, multiple: Boolean = false, selectedIndex: Int = -1, checkedStates: BooleanArray? = null): android.widget.ArrayAdapter<String> {
        // 不使用 CheckedTextView/ListView 的原生 checked 状态：部分电视 ROM 会在焦点移动、
        // setSelection 或视图复用时覆盖 isChecked。勾选图标由数据独立绘制，焦点只改变行背景。
        return object : android.widget.ArrayAdapter<String>(activity, 0, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout)?.takeIf { it.tag == "tv-choice-row" }
                    ?: LinearLayout(activity).apply {
                        tag = "tv-choice-row"
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 12))
                        minimumHeight = dp(activity, 52)
                        // 不能让行容器自己获得焦点或变成 clickable：
                        // 否则它会拦截 ListView 的 item click，导致单选/多选点了没有反应。
                        isFocusable = false
                        isFocusableInTouchMode = false
                        isClickable = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        addView(TextView(activity).apply {
                            tag = "tv-choice-label"
                            textSize = TvMetric.TEXT_LABEL
                            setTextColor(TvStyle.text)
                            gravity = Gravity.CENTER_VERTICAL
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(TextView(activity).apply {
                            tag = "tv-choice-mark"
                            textSize = 19f
                            gravity = Gravity.CENTER
                            minWidth = dp(activity, 30)
                            setTextColor(TvStyle.accent)
                            setTypeface(null, android.graphics.Typeface.BOLD)
                        }, LinearLayout.LayoutParams(dp(activity, 30), -2))
                        background = GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                            cornerRadius = dp(activity, 10).toFloat()
                        }
                    }
                (row.findViewWithTag<TextView>("tv-choice-label")).text = getItem(position)
                val checked = when {
                    checkedStates != null -> checkedStates.getOrNull(position) == true
                    !multiple && selectedIndex >= 0 -> position == selectedIndex
                    else -> (parent as? android.widget.AbsListView)?.isItemChecked(position) == true
                }
                row.findViewWithTag<TextView>("tv-choice-mark").text = if (checked) "✓" else ""
                return row
            }
        }
    }
    /** 列表选择弹窗：统一卡片 + 列表 + 底部按钮。 */
    fun choose(
        activity: Activity,
        title: String,
        labels: List<String>,
        onChoice: (Int) -> Unit,
        extra: String? = null,
        selectedIndex: Int = -1,
        onExtra: () -> Unit = {}
    ): AlertDialog {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title(activity, title))
        val list = android.widget.ListView(activity).apply {
            adapter = if(selectedIndex in labels.indices) {
                choiceAdapter(activity,labels,selectedIndex=selectedIndex)
            } else {
                listAdapter(activity,labels)
            }
        }
        capHeight(activity, list)
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(activity, 14), 0, 0)
        }
        if(extra != null) buttons.addView(capsuleButton(activity, extra) { onExtra() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(activity, 12) })
        val cancel = capsuleButton(activity, "取消") {}
        buttons.addView(cancel, LinearLayout.LayoutParams(-2, -2))
        content.addView(buttons)
        val dialog = AlertDialog.Builder(activity).setView(card(activity, content)).create()
        styleWindow(activity, dialog) { list.requestFocus() }
        cancel.setOnClickListener { dialog.dismiss() }
        list.setOnItemClickListener { _, _, index, _ -> dialog.dismiss(); onChoice(index) }
        return dialog
    }

    /** 信息弹窗：标题 + 正文 + 单按钮。 */
    fun info(activity: Activity, title: String, message: String): AlertDialog {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title(activity, title))
        content.addView(body(activity, message))
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(activity, 14), 0, 0)
        }
        val ok = capsuleButton(activity, "知道了") {}
        buttons.addView(ok, LinearLayout.LayoutParams(-2, -2))
        content.addView(buttons)
        val dialog = AlertDialog.Builder(activity).setView(card(activity, content)).create()
        styleWindow(activity, dialog) { ok.requestFocus() }
        ok.setOnClickListener { dialog.dismiss() }
        return dialog
    }

    /** 可滚动文本查看弹窗：沿用统一卡片和胶囊按钮，适合诊断信息与日志。 */
    fun textViewer(activity: Activity, title: String, message: String, monospace: Boolean = false): AlertDialog {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title(activity, title))
        val text = TextView(activity).apply {
            this.text = message
            textSize = if (monospace) 11f else 14f
            setTextColor(TvStyle.text)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            if (monospace) typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(activity, 2), dp(activity, 2), dp(activity, 8), dp(activity, 8))
        }
        val scroll = android.widget.ScrollView(activity).apply {
            isFillViewport = true
            addView(text, ViewGroup.LayoutParams(-1, -2))
        }
        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.52f).toInt()
        content.addView(scroll, LinearLayout.LayoutParams(-1, maxHeight.coerceAtLeast(dp(activity, 120))))
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(activity, 14), 0, 0)
        }
        val close = capsuleButton(activity, "关闭") {}
        buttons.addView(close, LinearLayout.LayoutParams(-2, -2))
        content.addView(buttons)
        val dialog = AlertDialog.Builder(activity).setView(card(activity, content)).create()
        styleWindow(activity, dialog, widthFraction = 0.72f, maxWidthDp = 760) { close.requestFocus() }
        close.setOnClickListener { dialog.dismiss() }
        return dialog
    }

    /** 确认弹窗：标题 + 正文 + 取消/确认双按钮。 */
    fun confirm(activity: Activity, title: String, message: String, onCancel: (() -> Unit)? = null, action: () -> Unit): AlertDialog {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title(activity, title))
        content.addView(body(activity, message))
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(activity, 14), 0, 0)
        }
        val cancel = capsuleButton(activity, "取消") {}
        val ok = capsuleButton(activity, "确认", primary = true) { action() }
        buttons.addView(cancel, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(activity, 12) })
        buttons.addView(ok, LinearLayout.LayoutParams(-2, -2))
        content.addView(buttons)
        val dialog = AlertDialog.Builder(activity).setView(card(activity, content)).create()
        styleWindow(activity, dialog) { cancel.requestFocus() }
        cancel.setOnClickListener { dialog.dismiss(); onCancel?.invoke() }
        dialog.setOnCancelListener { onCancel?.invoke() }
        ok.setOnClickListener { dialog.dismiss(); action() }
        return dialog
    }

    /** 限制滚动容器最大高度，避免长列表把弹窗撑出屏幕（post 到 attach 后执行，未 show 前调用也安全）。 */
    fun capHeight(activity: Activity, view: View, fraction: Float = 0.55f) {
        view.post {
            val cap = (activity.resources.displayMetrics.heightPixels * fraction).toInt()
            if(view.height > cap) { val p = view.layoutParams; p.height = cap; view.layoutParams = p }
        }
    }

    /** 通用弹窗窗口样式：透明背景、居中、限制最大宽度；onShown 合并进同一个 OnShowListener（AlertDialog 仅支持一个）。 */
    fun styleWindow(activity: Activity, dialog: AlertDialog, widthFraction: Float = 0.55f, maxWidthDp: Int = 620, onShown: (() -> Unit)? = null) {
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                val width = (activity.resources.displayMetrics.widthPixels * widthFraction).toInt().coerceAtMost(dp(activity, maxWidthDp))
                setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.CENTER)
            }
            onShown?.invoke()
        }
    }
}
