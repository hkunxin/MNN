// V2.1 Tab2 助理首页 · 对齐 ui-design.html 屏3：状态 chips + 分类摘要卡 + 入口卡；
// ⚙ 进「助理设置」（判定模型/高置信阈值，正式入口，沿用「判定模型不写死」定稿；
//   「仅亮屏处理」为锁定只读项——V2.1 定稿第 3 条，说明文字在设置对话框中呈现）。
package com.alibaba.mnnllm.android.assistant.notify

import android.content.Intent
import android.graphics.Typeface
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.debug.DebugActivity
import com.alibaba.mnnllm.android.model.ModelTypeUtils
import com.alibaba.mnnllm.android.modelist.LocalModelsProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AssistantTabFragment : Fragment() {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_assistant_tab, container, false)

        view.findViewById<View>(R.id.summaryCardsContainer).setOnClickListener {
            startActivity(Intent(requireContext(), SummaryActivity::class.java))
        }
        view.findViewById<View>(R.id.summaryEmpty).setOnClickListener {
            startActivity(Intent(requireContext(), SummaryActivity::class.java))
        }
        view.findViewById<View>(R.id.btnAssistantSettings).setOnClickListener { showAssistantSettings() }
        view.findViewById<View>(R.id.btnRuleSuggestions).setOnClickListener {
            startActivity(Intent(requireContext(), RuleSuggestionsActivity::class.java))
        }
        view.findViewById<View>(R.id.btnBlockLog).setOnClickListener {
            startActivity(Intent(requireContext(), BlockLogActivity::class.java))
        }
        // 三态：未授权→去系统设置；已授权未连接→强制重新绑定（覆盖安装丢绑定）；
        // 已连接→仍然跳设置（方便用户关掉或改别的应用）。
        view.findViewById<View>(R.id.btnNotifyAccess).setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            when {
                !NotificationAccess.isGranted(ctx) -> NotificationAccess.openSettings(ctx)
                !SmartNotificationListener.isConnected() -> {
                    val did = NotificationAccess.ensureBound(ctx)
                    Toast.makeText(
                        ctx,
                        if (did) "正在重新绑定通知监听…" else "刚试过，稍等几秒再看状态",
                        Toast.LENGTH_SHORT
                    ).show()
                    view.postDelayed({ refresh() }, 1500)
                }
                else -> NotificationAccess.openSettings(ctx)
            }
        }
        view.findViewById<View>(R.id.btnOpenDebug).setOnClickListener {
            startActivity(Intent(requireContext(), DebugActivity::class.java))
        }
        return view
    }

    override fun onResume() {
        super.onResume()
        // 覆盖安装 APK 会掉绑定却保留授权：进助理页时顺手自愈一次（内部 15s 节流，
        // 避免反复 cycle 组件）。绑定是异步的，所以稍后再刷一次状态。
        val ctx = context
        if (ctx != null && NotificationAccess.ensureBound(ctx)) {
            view?.postDelayed({ refresh() }, 1500)
        }
        refresh()
    }

    private fun refresh() {
        val ctx = context ?: return
        val root = view ?: return

        // ---- 状态 chips（屏3）----
        val chipLast = root.findViewById<TextView>(R.id.chipLastSummary)
        val snap = SummaryStore(ctx).get()
        chipLast.text = if (snap != null && snap.generatedAt > 0) {
            "上次总结 ${timeFmt.format(Date(snap.generatedAt))}"
        } else "上次总结 —"

        root.findViewById<TextView>(R.id.chipQueue).text =
            "待判队列 ${PendingQueueStore(ctx).size()} 条"

        // ---- 摘要分类卡片（屏3 主体）----
        val container = root.findViewById<LinearLayout>(R.id.summaryCardsContainer)
        val empty = root.findViewById<View>(R.id.summaryEmpty)
        container.removeAllViews()
        val parsed = snap?.let { SummarizePrompt.parseSummary(it.text) }
        if (parsed.isNullOrEmpty()) {
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
            for ((name, items) in parsed) {
                container.addView(buildCategoryCard(name, items))
            }
        }

        // ---- 入口卡角标 ----
        val pending = LearnedRuleStore(ctx).pending().size
        val badge = root.findViewById<TextView>(R.id.badgeRulePending)
        if (pending > 0) {
            badge.visibility = View.VISIBLE
            badge.text = "$pending 条待确认"
        } else badge.visibility = View.GONE

        // 三态展示：已连接 / 已授权未连接（覆盖安装后典型死状态）/ 未授权。
        val connected = SmartNotificationListener.isConnected()
        val granted = NotificationAccess.isGranted(ctx)
        val accessLabel = root.findViewById<TextView>(R.id.notifyAccessLabel)
        val accessBadge = root.findViewById<TextView>(R.id.badgeNotifyAccess)
        when {
            connected -> {
                accessLabel.text = "通知使用权"
                accessBadge.visibility = View.VISIBLE
                accessBadge.text = "已连接"
                accessBadge.setBackgroundColor(0xFFE6F4EA.toInt())
                accessBadge.setTextColor(0xFF188038.toInt())
            }
            granted -> {
                accessLabel.text = "通知使用权：已授权但监听器未连接，点此修复"
                accessBadge.visibility = View.VISIBLE
                accessBadge.text = "未连接"
                accessBadge.setBackgroundColor(0xFFFEF7E0.toInt())
                accessBadge.setTextColor(0xFFB06000.toInt())
            }
            else -> {
                accessLabel.text = "通知使用权：未授权，点此开启"
                accessBadge.visibility = View.VISIBLE
                accessBadge.text = "未授权"
                accessBadge.setBackgroundColor(0xFFFCE8E6.toInt())
                accessBadge.setTextColor(0xFFD93025.toInt())
            }
        }
    }

    /** 一张分类卡：标题（emoji + 名称 + 计数）+ 条目列表（屏3 .card 结构）。 */
    private fun buildCategoryCard(name: String, items: List<String>): LinearLayout {
        val ctx = requireContext()
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.bg_card_white, ctx.theme)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            // 卡间距：顶部 8dp（首卡外由容器 padding 承担）
            (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(8)
        }

        val head = TextView(ctx).apply {
            text = "${emojiFor(name)} $name  ${items.size} 条"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1B1F27.toInt())
        }
        card.addView(head)

        items.take(4).forEach { item ->
            val row = TextView(ctx).apply {
                text = "·  $item"
                textSize = 12f
                setTextColor(0xFF3C4457.toInt())
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(5), 0, 0)
            }
            card.addView(row)
        }
        if (items.size > 4) {
            val more = TextView(ctx).apply {
                text = "… 共 ${items.size} 条，点开查看全部"
                textSize = 11f
                setTextColor(0xFF1A73E8.toInt())
                setPadding(0, dp(5), 0, 0)
            }
            card.addView(more)
        }

        // MarginLayoutParams 需在加入容器前设置
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
        return card
    }

    private fun emojiFor(name: String): String = when {
        name.contains("日程") || name.contains("提醒") -> "📅"
        name.contains("社交") || name.contains("通信") -> "💬"
        name.contains("系统") -> "🛠"
        name.contains("营销") || name.contains("推广") -> "🛍"
        name.contains("验证") -> "🔐"
        else -> "📌"
    }

    /** ⚙ 助理设置二级（屏3 定稿落点）：模型选择 + 高置信阈值 + 省电策略说明。 */
    private fun showAssistantSettings() {
        val ctx = context ?: return
        val model = AssistantNotifyConfig.judgeModelIdStatic(ctx)?.ifEmpty { null } ?: "未设置"
        val threshold = "%.2f".format(AssistantNotifyConfig.ruleAutoConfirmThresholdStatic(ctx))
        MaterialAlertDialogBuilder(ctx)
            .setTitle("助理设置")
            .setItems(arrayOf(
                "判定 / 摘要 / 纪要模型：$model",
                "高置信自动屏蔽阈值：$threshold",
                "省电策略：仅亮屏处理（已锁定，息屏零推理）"
            )) { _, which ->
                when (which) {
                    0 -> pickJudgeModel()
                    1 -> pickThreshold()
                }
            }
            .show()
    }

    /** 判定模型选择（已装文本模型列表；V2.1 模型选择收拢决策的正式入口）。 */
    private fun pickJudgeModel() {
        val ctx = context ?: return
        val models = try {
            LocalModelsProvider.getLocalModels()
        } catch (e: Exception) {
            emptyList()
        }.filter { item ->
            val path = item.localPath
            path != null && File(path, "config.json").exists() &&
                item.modelId != null && !ModelTypeUtils.isAudioModel(item.modelId!!)
        }.map { it.modelId!! }

        if (models.isEmpty()) {
            Toast.makeText(context, "没有已导入的文本模型，请先到模型管理下载/导入", Toast.LENGTH_LONG).show()
            return
        }
        val current = AssistantNotifyConfig.judgeModelIdStatic(ctx)
        val labels = models.map { if (it == current) "✓ $it" else it }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择判定 / 摘要 / 纪要模型")
            .setItems(labels) { _, which ->
                AssistantNotifyConfig.setJudgeModelId(ctx, models[which])
                refresh()
                Toast.makeText(context, "已设置: ${models[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("清除设置") { _, _ ->
                AssistantNotifyConfig.setJudgeModelId(ctx, "")
                refresh()
            }
            .show()
    }

    private fun pickThreshold() {
        val ctx = context ?: return
        val options = arrayOf("0.70", "0.80", "0.90", "0.95")
        MaterialAlertDialogBuilder(ctx)
            .setTitle("高置信自动屏蔽阈值\n（达到即跳过确认直接生效）")
            .setItems(options) { _, which ->
                AssistantNotifyConfig.setRuleAutoConfirmThreshold(ctx, options[which].toDouble())
                refresh()
            }
            .show()
    }
}
