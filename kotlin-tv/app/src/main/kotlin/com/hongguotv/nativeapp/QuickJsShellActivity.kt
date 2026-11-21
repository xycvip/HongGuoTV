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
import org.json.JSONObject

/**
 * Minimal native shell: Kotlin owns the screen and interaction; the bundled JS module owns API logic.
 */
class QuickJsShellActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var runtime: QuickJsRuntime? = null
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var queryInput: EditText
    private lateinit var searchButton: Button
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
        statusView = TextView(this).apply {
            text = "正在启动 QuickJS…"
            textSize = 18f
            setTextColor(0xffb9c3d0.toInt())
        }
        val heading = TextView(this).apply {
            text = "红果 TV"
            textSize = 30f
            setTextColor(0xffffffff.toInt())
        }
        queryInput = EditText(this).apply {
            hint = "输入短剧名称"
            singleLine = true
            textSize = 18f
            setTextColor(0xffffffff.toInt())
            setHintTextColor(0xff8994a3.toInt())
        }
        searchButton = Button(this).apply {
            text = "搜索"
            isEnabled = false
            setOnClickListener { runSearch(queryInput.text.toString().trim()) }
        }
        resultView = TextView(this).apply {
            text = "等待 JS 模块加载"
            textSize = 16f
            setTextColor(0xffe4e9f0.toInt())
            setPadding(0, 18, 0, 0)
        }
        root.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })
        root.addView(queryInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 22 })
        root.addView(searchButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })
        val scroll = ScrollView(this).apply {
            addView(resultView)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 12 })
        setContentView(root)
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
                        try {
                            module.loadInto(engine)
                        } catch (error: Throwable) {
                            engine.close()
                            throw error
                        }
                    }
                }
                runtime = created
                statusView.text = "JS 模块已加载 · QuickJS 1.0.18"
                resultView.text = "模块已就绪。输入剧名即可调用原版 JS 搜索接口。"
                searchButton.isEnabled = true
            } catch (error: Throwable) {
                statusView.text = "模块启动失败"
                resultView.text = error.message ?: error.javaClass.simpleName
            }
        }
    }

    private fun runSearch(keyword: String) {
        if (keyword.isBlank()) {
            resultView.text = "请输入剧名"
            return
        }
        val active = runtime ?: return
        searchButton.isEnabled = false
        resultView.text = "正在搜索：$keyword"
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    module.invoke(active, "search", "[1," + JSONObject.quote(keyword) + "]")
                }
                resultView.text = prettyJson(json)
            } catch (error: Throwable) {
                resultView.text = "搜索失败： " + (error.message ?: error.javaClass.simpleName)
            } finally {
                searchButton.isEnabled = true
            }
        }
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
