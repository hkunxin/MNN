// Spec-002 task 6: 规则建议/沉淀管理页 — 确认/撤销 UI（功能版）。
// 待确认建议：忽略 / 加入屏蔽；已确认规则：撤销（回 pending 语义为删除）。
// 完成判据：确认后的规则对此后同类广告即时拦截（不经过 LLM，见监听链 learned 分支）。
package com.alibaba.mnnllm.android.assistant.notify

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.mnnllm.android.R

class RuleSuggestionsActivity : AppCompatActivity() {

    private lateinit var store: LearnedRuleStore
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_suggestions)
        store = LearnedRuleStore(this)
        listContainer = findViewById(R.id.ruleListContainer)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        val pending = store.pending()
        val confirmed = store.confirmed()

        addSectionHeader("待确认建议（${pending.size}）")
        if (pending.isEmpty()) {
            addNote("暂无待确认建议。LLM 判定为广告且给出规则后会出现在这里。")
        }
        for (r in pending) addRuleRow(r, isPending = true)

        addSectionHeader("已生效规则（${confirmed.size}）")
        if (confirmed.isEmpty()) {
            addNote("暂无已生效的学习规则。确认后的规则对同类广告即时拦截，不再经过 LLM。")
        }
        for (r in confirmed) addRuleRow(r, isPending = false)
    }

    private fun addSectionHeader(title: String) {
        val tv = TextView(this).apply {
            text = title
            textSize = 15f
            setPadding(0, dp(16), 0, dp(6))
            setTextColor(0xFF333333.toInt())
        }
        listContainer.addView(tv)
    }

    private fun addNote(note: String) {
        val tv = TextView(this).apply {
            text = note
            textSize = 13f
            setPadding(0, dp(4), 0, dp(4))
            setTextColor(0xFF888888.toInt())
        }
        listContainer.addView(tv)
    }

    private fun addRuleRow(r: LearnedRuleStore.Rule, isPending: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x14000000)
                cornerRadius = dp(10).toFloat()
            }
        }
        row.addView(TextView(this).apply {
            text = "规则: ${r.pattern}"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        row.addView(TextView(this).apply {
            text = "包名: ${if (r.pkg.isEmpty()) "任意应用" else r.pkg}   置信度: ${"%.2f".format(r.conf)}"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
        })
        if (r.sample.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = "样本: ${r.sample}"
                textSize = 12f
                maxLines = 2
                setTextColor(0xFF666666.toInt())
            })
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(6), 0, 0)
        }
        if (isPending) {
            buttons.addView(makeButton("忽略") {
                store.dismiss(r.id)
                Toast.makeText(this, "已忽略", Toast.LENGTH_SHORT).show()
                render()
            })
            buttons.addView(makeButton("加入屏蔽") {
                store.confirm(r.id)
                Toast.makeText(this, "已生效：同类广告将即时拦截", Toast.LENGTH_SHORT).show()
                render()
            })
        } else {
            buttons.addView(makeButton("撤销") {
                store.remove(r.id)
                Toast.makeText(this, "已撤销", Toast.LENGTH_SHORT).show()
                render()
            })
        }
        row.addView(buttons)
        listContainer.addView(row)
        listContainer.addView(TextView(this).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
