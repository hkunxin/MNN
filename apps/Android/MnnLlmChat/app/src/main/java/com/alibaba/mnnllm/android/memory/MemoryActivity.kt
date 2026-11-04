// Spec 005 task 6: 记忆管理页。
//
// 与 V2.1 其他二级页一致（BlockLogActivity / RuleSuggestionsActivity）：
// 静态 XML 骨架 + 代码构建条目，避免为一个列表再引入 Adapter/ViewHolder 资源。
// 记忆量级受 MemoryStore.MAX_ITEMS 约束（200），简单视图足够。
package com.alibaba.mnnllm.android.memory

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.assistant.notify.AssistantNotifyConfig
import com.alibaba.mnnllm.android.utils.SystemBarInsets
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var countView: TextView
    private lateinit var autoSwitch: SwitchCompat

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)
        // targetSdk 35：Android 15 强制 edge-to-edge，需显式避让状态栏/导航栏
        SystemBarInsets.applyPadding(this)

        container = findViewById(R.id.memoryContainer)
        countView = findViewById(R.id.memoryCount)
        autoSwitch = findViewById(R.id.memoryAutoSwitch)

        findViewById<TextView>(R.id.memoryBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.memoryClear).setOnClickListener { confirmClear() }
        findViewById<View>(R.id.memoryAdd).setOnClickListener { showEditDialog(null) }
        setupAutoExtractSwitch()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ------------------------------------------------------------ 自动记忆开关

    private fun setupAutoExtractSwitch() {
        autoSwitch.isChecked = MemoryStore.isAutoExtractEnabled(this)
        autoSwitch.setOnCheckedChangeListener { _, checked ->
            MemoryStore.setAutoExtractEnabled(this, checked)
            if (checked) {
                val judgeModel = AssistantNotifyConfig.judgeModelIdStatic(this)
                if (judgeModel.isNullOrBlank()) {
                    // 开关本身是用户意图，保留；只是提示依赖项未就绪（R9：无模型时静默跳过抽取）。
                    Toast.makeText(this, R.string.memory_auto_extract_needs_model, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ------------------------------------------------------------ 渲染

    private fun render() {
        val items = MemoryStore.all()
        val injected = MemoryStore.buildPromptBlock().count
        countView.text = getString(R.string.memory_count, items.size, injected)

        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.memory_empty)
                textSize = 13f
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(0xFF888888.toInt())
                setPadding(dp(8), dp(32), dp(8), 0)
            })
            return
        }
        items.forEach { container.addView(buildRow(it)) }
    }

    private fun buildRow(item: MemoryItem): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply {
                setColor(if (item.enabled) 0x0F000000 else 0x08000000)
                cornerRadius = dp(10).toFloat()
            }
        }

        val check = CheckBox(this).apply {
            isChecked = item.enabled
            setOnCheckedChangeListener { _, checked ->
                MemoryStore.setEnabled(item.id, checked)
                // 回调里正在处理自身视图的点击，重建列表放到下一帧，避免移除焦点控件时的错乱。
                container.post { render() }
            }
        }
        row.addView(check)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4), 0, dp(8), 0)
            isClickable = true
            setOnClickListener { showEditDialog(item) }
        }
        texts.addView(TextView(this).apply {
            text = item.content
            textSize = 14f
            setTypeface(typeface, if (item.enabled) Typeface.NORMAL else Typeface.ITALIC)
            setTextColor(if (item.enabled) 0xFF202124.toInt() else 0xFF9AA0A6.toInt())
        })
        val sourceLabel = getString(
            if (item.isManual) R.string.memory_source_manual else R.string.memory_source_auto
        )
        texts.addView(TextView(this).apply {
            text = "$sourceLabel · ${timeFormat.format(Date(if (item.updatedAt > 0) item.updatedAt else item.createdAt))}"
            textSize = 11f
            setTextColor(0xFF9AA0A6.toInt())
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(texts)

        row.addView(TextView(this).apply {
            text = getString(R.string.memory_delete)
            textSize = 12f
            setTextColor(0xFFD93025.toInt())
            setPadding(dp(8), dp(6), dp(6), dp(6))
            isClickable = true
            setOnClickListener {
                MemoryStore.delete(item.id)
                Toast.makeText(this@MemoryActivity, R.string.memory_deleted, Toast.LENGTH_SHORT).show()
                render()
            }
        })

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        wrapper.addView(row)
        return wrapper
    }

    // ------------------------------------------------------------ 编辑 / 清空

    private fun showEditDialog(item: MemoryItem?) {
        val input = EditText(this).apply {
            setText(item?.content ?: "")
            hint = getString(R.string.memory_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (item == null) R.string.memory_add else R.string.memory_edit)
            .setView(input, dp(20), dp(8), dp(20), 0)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString()
                if (text.isBlank()) return@setPositiveButton
                if (item == null) {
                    MemoryStore.addManual(text)
                } else {
                    MemoryStore.update(item.id, text, item.enabled)
                }
                Toast.makeText(this, R.string.memory_saved, Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.memory_clear_confirm_title)
            .setMessage(R.string.memory_clear_confirm_message)
            .setPositiveButton(R.string.memory_delete) { _, _ ->
                MemoryStore.clear()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
