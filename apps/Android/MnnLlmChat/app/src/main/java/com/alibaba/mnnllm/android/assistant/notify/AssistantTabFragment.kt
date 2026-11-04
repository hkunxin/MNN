// V2.1 Tab2 助理首页：通知摘要卡 + 规则建议/拦截动态入口 + 助理设置
// （判定模型选择=正式入口，沿用用户定稿「判定模型不写死」；高置信阈值可调；
//   「仅亮屏处理」为锁定只读项——V2.1 定稿第 3 条）。
package com.alibaba.mnnllm.android.assistant.notify

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.debug.DebugActivity
import com.alibaba.mnnllm.android.model.ModelTypeUtils
import com.alibaba.mnnllm.android.modelist.LocalModelsProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class AssistantTabFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_assistant_tab, container, false)

        view.findViewById<View>(R.id.cardSummary).setOnClickListener {
            startActivity(Intent(requireContext(), SummaryActivity::class.java))
        }
        view.findViewById<View>(R.id.btnOpenSummary).setOnClickListener {
            startActivity(Intent(requireContext(), SummaryActivity::class.java))
        }
        view.findViewById<View>(R.id.btnRuleSuggestions).setOnClickListener {
            startActivity(Intent(requireContext(), RuleSuggestionsActivity::class.java))
        }
        view.findViewById<View>(R.id.btnBlockLog).setOnClickListener {
            startActivity(Intent(requireContext(), BlockLogActivity::class.java))
        }
        view.findViewById<View>(R.id.btnNotifyAccess).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(context, "无法打开通知使用权设置", Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<View>(R.id.rowJudgeModel).setOnClickListener { pickJudgeModel() }
        view.findViewById<View>(R.id.rowAutoThreshold).setOnClickListener { pickThreshold() }
        view.findViewById<View>(R.id.btnOpenDebug).setOnClickListener {
            startActivity(Intent(requireContext(), DebugActivity::class.java))
        }
        return view
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val ctx = context ?: return
        // 摘要预览
        val snap = SummaryStore(ctx).get()
        val preview = view?.findViewById<TextView>(R.id.assistantSummaryPreview)
        if (snap == null) {
            preview?.text = "（尚无摘要，点「打开摘要页」生成）"
        } else {
            val parsed = SummarizePrompt.parseSummary(snap.text)
            val text = if (parsed != null) {
                parsed.joinToString("\n") { (name, items) ->
                    "■ $name（${items.size}）\n" + items.take(3).joinToString("\n") { "  · $it" }
                }
            } else snap.text
            preview?.text = text
        }
        // 规则建议角标
        val pending = LearnedRuleStore(ctx).pending().size
        view?.findViewById<TextView>(R.id.btnRuleSuggestions)?.text =
            if (pending > 0) "规则建议（$pending 条待确认）" else "规则建议"
        // 通知使用权
        view?.findViewById<TextView>(R.id.btnNotifyAccess)?.text =
            if (SmartNotificationListener.isConnected()) "通知使用权：已连接 ✓"
            else "通知使用权：未授权，点此开启"
        // 助理设置值
        view?.findViewById<TextView>(R.id.judgeModelValue)?.text =
            AssistantNotifyConfig.judgeModelIdStatic(ctx)?.ifEmpty { "未设置" } ?: "未设置"
        view?.findViewById<TextView>(R.id.autoThresholdValue)?.text =
            "%.2f".format(AssistantNotifyConfig.ruleAutoConfirmThresholdStatic(ctx))
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("高置信自动屏蔽阈值\n（达到即跳过确认直接生效）")
            .setItems(options) { _, which ->
                val v = options[which].toDouble()
                AssistantNotifyConfig.setRuleAutoConfirmThreshold(ctx, v)
                refresh()
            }
            .show()
    }
}
