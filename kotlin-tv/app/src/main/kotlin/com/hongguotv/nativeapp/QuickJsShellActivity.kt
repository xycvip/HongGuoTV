package com.hongguotv.nativeapp

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hongguotv.quickjs.QuickJsRuntime
import com.hongguotv.quickjs.installHostAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Kotlin owns interaction and presentation; the original JS module owns API/business logic. */
class QuickJsShellActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var runtime: QuickJsRuntime? = null
    private lateinit var statusView: TextView
    private lateinit var queryInput: EditText
    private lateinit var searchButton: Button
    private lateinit var content: LinearLayout
    private val module by lazy { HongguoJsModule(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        initializeModule()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 28, 40, 24)
            gravity = Gravity.TOP
            setBackgroundColor(0xff101318.toInt())
        }
        val heading = TextView(this).apply {
            text = "红果 TV · 原生接口验证"
            textSize = 28f
            setTextColor(0xffffffff.toInt())
        }
        statusView = TextView(this).apply {
            text = "正在启动 QuickJS…"
            textSize = 16f
            setTextColor(0xffb9c3d0.toInt())
        }
        queryInput = EditText(this).apply {
            hint = "输入短剧名称"
            setSingleLine(true)
            textSize = 18f
            setTextColor(0xffffffff.toInt())
            setHintTextColor(0xff8994a3.toInt())
        }
        searchButton = Button(this).apply {
            text = "搜索短剧"
            isEnabled = false
            setOnClickListener { runSearch(queryInput.text.toString().trim()) }
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 24)
        }
        addMessage("正在加载原版红果 JS 模块…")
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.addView(heading)
        root.addView(statusView, margin(8))
        root.addView(queryInput, margin(18))
        root.addView(searchButton, margin(6))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 12 })
        setContentView(root)
    }

    private fun margin(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }

    private fun addMessage(message: String) {
        content.addView(TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(0xffe4e9f0.toInt())
            setPadding(0, 12, 0, 12)
        }, margin(4))
    }

    private fun addAction(label: String, action: () -> Unit) {
        content.addView(Button(this).apply {
            text = label
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { action() }
        }, margin(5))
    }

    private fun initializeModule() {
        scope.launch {
            statusView.text = "正在初始化 QuickJS 运行环境…"
            try {
                val created = withContext(Dispatchers.IO) {
                    QuickJsRuntime.create(
                        loadModule = { null },
                        dispatcher = Dispatchers.IO,
                        installBindings = { engine -> engine.installHostAdapter(HongguoHostAdapter(this@QuickJsShellActivity)) },
                    ).also { engine ->
                        try { module.loadInto(engine) } catch (error: Throwable) {
                            engine.close()
                            throw error
                        }
                    }
                }
                runtime = created
                statusView.text = "JS 模块已加载 · QuickJS 1.0.18"
                content.removeAllViews()
                addMessage("模块已就绪。搜索结果可进入详情，并测试首集取流。")
                searchButton.isEnabled = true
            } catch (error: Throwable) {
                statusView.text = "模块启动失败"
                showError(error)
            }
        }
    }

    private fun runSearch(keyword: String) {
        if (keyword.isBlank()) {
            content.removeAllViews()
            addMessage("请输入剧名")
            return
        }
        val active = runtime ?: return
        searchButton.isEnabled = false
        content.removeAllViews()
        addMessage("正在搜索：$keyword")
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    module.invoke(active, "search", "[1," + JSONObject.quote(keyword) + "]")
                }
                renderSearch(json)
            } catch (error: Throwable) {
                showError(error)
            } finally {
                searchButton.isEnabled = true
            }
        }
    }

    private fun renderSearch(raw: String) {
        content.removeAllViews()
        val result = JSONObject(raw)
        val items = result.optJSONArray("items") ?: JSONArray()
        val warning = result.optString("warning")
        addMessage("搜索完成：${items.length()} 条结果")
        if (warning.isNotBlank()) addMessage("提示：$warning")
        for (index in 0 until items.length()) {
            val drama = items.optJSONObject(index) ?: continue
            val title = drama.optString("title").ifBlank { drama.optString("name", "未命名短剧") }
            val episodes = drama.optInt("episodeCount", drama.optInt("totalEpisode"))
            val subtitle = buildString {
                append(title)
                if (episodes > 0) append("  ·  $episodes 集")
                drama.optString("remark").takeIf { it.isNotBlank() }?.let { append("  ·  ").append(it) }
            }
            val sourceId = drama.optString("sourceId")
            addAction(subtitle) {
                if (sourceId.isBlank()) {
                    content.removeAllViews()
                    addMessage("该结果缺少 sourceId，无法请求详情")
                } else runDetail(sourceId, title)
            }
        }
        if (items.length() == 0) addMessage("没有找到短剧结果。可检查上方提示或稍后重试。")
    }

    private fun runDetail(sourceId: String, title: String) {
        val active = runtime ?: return
        content.removeAllViews()
        addMessage("正在获取详情：$title")
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    module.invoke(active, "detail", "[" + JSONObject.quote(sourceId) + "]")
                }
                renderDetail(raw, sourceId, title)
            } catch (error: Throwable) {
                showError(error)
            }
        }
    }

    private fun renderDetail(raw: String, sourceId: String, fallbackTitle: String) {
        content.removeAllViews()
        val detail = JSONObject(raw)
        val drama = detail.optJSONObject("drama") ?: JSONObject()
        val title = drama.optString("title").ifBlank { fallbackTitle }
        val dramaId = drama.optString("id")
        val chapters = detail.optJSONArray("chapters") ?: JSONArray()
        addMessage("$title  ·  共 ${chapters.length()} 集")
        addAction("返回搜索结果") { runSearch(queryInput.text.toString().trim()) }
        for (index in 0 until chapters.length()) {
            val chapter = chapters.optJSONObject(index) ?: continue
            val episode = chapter.optInt("currentEpisode", index + 1)
            val chapterTitle = chapter.optString("title").ifBlank { "第 $episode 集" }
            addAction("$chapterTitle  ·  解析测试") {
                runResolve(dramaId, sourceId, chapter, episode)
            }
        }
        if (chapters.length() == 0) addMessage("详情接口没有返回分集。")
    }

    private fun runResolve(dramaId: String, sourceId: String, chapter: JSONObject, episode: Int) {
        val active = runtime ?: return
        content.removeAllViews()
        addMessage("正在解析第 $episode 集…")
        scope.launch {
            try {
                val task = JSONObject().put("dramaId", dramaId).put("chapter", chapter)
                val raw = withContext(Dispatchers.IO) {
                    module.invoke(active, "resolveMedia", "[" + task.toString() + "]")
                }
                content.removeAllViews()
                addMessage("第 $episode 集解析返回：")
                addMessage(prettyJson(raw))
                addAction("返回详情") { runDetail(sourceId, "短剧") }
            } catch (error: Throwable) {
                showError(error)
            }
        }
    }

    private fun showError(error: Throwable) {
        content.removeAllViews()
        addMessage("操作失败：${error.message ?: error.javaClass.simpleName}")
        addMessage(error.stackTraceToString())
    }

    private fun prettyJson(raw: String): String = try {
        JSONObject(raw).toString(2)
    } catch (_: Throwable) {
        raw
    }

    override fun onDestroy() {
        scope.cancel()
        runtime?.close()
        runtime = null
        super.onDestroy()
    }
}
