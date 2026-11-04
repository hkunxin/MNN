// Spec-002 task 7 (R5): 摘要页 — 分类罗列（验证码/日程/社交通信/系统/营销/其他）
// + 亮屏按需即时推理（复用 :llm 进程文件交换协议，mode="summary"）
// + 息屏/离线展示上次结果（SummaryStore 缓存） + 清空入口。
// 降级：模型输出无法解析为分类 JSON 时，直接展示原始文本（不报错、不空白）。
package com.alibaba.mnnllm.android.assistant.notify

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.utils.SystemBarInsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SummaryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SmartNotify"
    }

    private lateinit var statusView: TextView
    private lateinit var contentView: TextView
    private lateinit var generateButton: TextView
    private lateinit var clearButton: TextView

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_summary)
        // targetSdk 35：Android 15 强制 edge-to-edge，需显式避让状态栏/导航栏
        SystemBarInsets.applyPadding(this)
        statusView = findViewById(R.id.summaryStatus)
        contentView = findViewById(R.id.summaryContent)
        generateButton = findViewById(R.id.btnGenerateSummary)
        clearButton = findViewById(R.id.btnClearSummary)

        generateButton.setOnClickListener { generate() }
        clearButton.setOnClickListener {
            DigestStore(this).clear()
            SummaryStore(this).clear()
            renderCached("(已清空)")
            Toast.makeText(this, "摘要库与缓存已清空", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val snap = SummaryStore(this).get()
        if (snap == null) {
            renderCached("（尚无摘要，点「生成摘要」开始）")
        } else {
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(Date(snap.generatedAt))
            render(snap.text, "上次结果 · $time · ${snap.modelId}")
        }
    }

    /** 亮屏按需即时推理：用户点按钮才调用模型（R2 只约束息屏后台）。 */
    private fun generate() {
        val items = DigestStore(this).recent()
        if (items.isEmpty()) {
            Toast.makeText(this, "24h 内没有待摘要的通知", Toast.LENGTH_SHORT).show()
            return
        }
        val modelId = AssistantNotifyConfig.judgeModelIdStatic(this)
        if (modelId.isNullOrEmpty()) {
            Toast.makeText(this, "请先在设置中指定判定/摘要模型", Toast.LENGTH_LONG).show()
            return
        }
        generateButton.isEnabled = false
        statusView.text = "推理中…（${items.size} 条，端侧模型，请稍候）"
        contentView.text = ""

        CoroutineScope(Dispatchers.IO).launch {
            var result: JSONObject? = null
            try {
                val req = JSONObject().apply {
                    put("mode", "summary")
                    put("modelId", modelId)
                    put("configPath",
                        com.alibaba.mnnllm.android.model.ModelUtils.getConfigPathForModel(modelId))
                    val arr = JSONArray()
                    for (it in items) {
                        arr.put(JSONObject().apply {
                            put("pkg", it.pkg); put("title", it.title)
                            put("text", it.text); put("postedAt", it.postedAt)
                        })
                    }
                    put("items", arr)
                }
                result = AssistantLlmClient.request(applicationContext, req)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "summary generate failed", t)
            }
            withContext(Dispatchers.Main) {
                generateButton.isEnabled = true
                val json = result
                if (json == null) {
                    statusView.text = "生成失败（超时或服务异常），上次结果仍保留"
                    return@withContext
                }
                if (!json.optBoolean("ok", false)) {
                    statusView.text = "生成失败: " + json.optString("error", "?")
                    return@withContext
                }
                val text = json.optString("text", "")
                SummaryStore(applicationContext).put(text, modelId)
                render(text, "刚刚生成 · ${json.optLong("costMs", 0)}ms · $modelId")
            }
        }
    }

    private fun renderCached(hint: String) {
        statusView.text = hint
        contentView.text = ""
    }

    private fun render(rawText: String, status: String) {
        statusView.text = status
        // 解析失败降级纯文本：parseSummary null -> 原样展示。
        val categories = SummarizePrompt.parseSummary(rawText)
        if (categories != null) {
            val sb = StringBuilder()
            for ((name, items) in categories) {
                sb.append("■ ").append(name).append("（").append(items.size).append("）\n")
                for (it in items) {
                    sb.append("  · ").append(it).append('\n')
                }
                sb.append('\n')
            }
            contentView.text = sb.toString().trim()
        } else {
            contentView.text = rawText.ifBlank { "（模型未输出内容）" }
        }
    }
}
