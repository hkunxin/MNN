// V2.1 助理·拦截动态页：BlockLogStore 时间线（正则拦截 + LLM 广告撤回 + 学习规则拦截）。
// V2.1 定稿第 3 条：兼 task 10 电量/时效验收的观测面。
package com.alibaba.mnnllm.android.assistant.notify

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.mnnllm.android.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BlockLogActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_block_log)
        container = findViewById(R.id.blockLogContainer)
        findViewById<TextView>(R.id.blockLogBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.blockLogClear).setOnClickListener {
            BlockLogStore.reset(this)
            Toast.makeText(this, "拦截记录已清空", Toast.LENGTH_SHORT).show()
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        val entries = BlockLogStore.readAll(this)
        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无拦截记录。命中屏蔽规则 / LLM 判定广告 / 学习规则的\n通知会出现在这条时间线上。"
                textSize = 13f
                setPadding(0, dp(24), 0, 0)
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(0xFF888888.toInt())
            })
            return
        }
        container.addView(TextView(this).apply {
            text = "共 ${entries.size} 条"
            textSize = 12f
            setPadding(dp(4), 0, 0, dp(8))
            setTextColor(0xFF888888.toInt())
        })
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        for (e in entries) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x14000000)
                    cornerRadius = dp(10).toFloat()
                }
            }
            card.addView(TextView(this).apply {
                text = "${fmt.format(Date(e.time))}  ${e.pkg}"
                textSize = 11f
                setTextColor(0xFF888888.toInt())
            })
            card.addView(TextView(this).apply {
                text = e.title.ifEmpty { "（无标题）" }
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(2), 0, 0)
            })
            if (e.text.isNotEmpty()) {
                card.addView(TextView(this).apply {
                    text = e.text
                    textSize = 12f
                    maxLines = 3
                    setTextColor(0xFF666666.toInt())
                })
            }
            card.addView(TextView(this).apply {
                text = "规则: ${e.rule}"
                textSize = 11f
                setTextColor(0xFF1E88E5.toInt())
                setPadding(0, dp(4), 0, 0)
            })
            container.addView(card)
            container.addView(TextView(this).apply { setPadding(0, dp(6), 0, 0) })
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
