// V2.1 Tab1 对话 · 对齐 ui-design.html 屏1：appbar + 会话列表（复用 ChatHistoryFragment，
// hideTitle 隐藏内嵌标题）+ FAB ＋（展开两项：新建对话 / 开始会议）。
package com.alibaba.mnnllm.android.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.assistant.notify.meeting.MeetingActivity
import com.alibaba.mnnllm.android.history.ChatHistoryFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ChatTabFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_chat_tab, container, false)
        view.findViewById<View>(R.id.btnMeetingEntry).setOnClickListener {
            startActivity(Intent(requireContext(), MeetingActivity::class.java))
        }
        view.findViewById<View>(R.id.fabNew).setOnClickListener { showFabMenu() }

        // 会话列表（隐藏内嵌「对话历史」标题，appbar 已承担；V2.1 页内无模型选择入口）
        val history = ChatHistoryFragment().apply {
            arguments = Bundle().apply { putBoolean(ChatHistoryFragment.ARG_HIDE_TITLE, true) }
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.chatListContainer, history)
            .commit()
        return view
    }

    /** 设计稿 FAB 决策：点击展开两项「💬 新建对话 / 🎙 开始会议」。 */
    private fun showFabMenu() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("开始")
            .setItems(arrayOf("💬  新建对话", "🎙  开始会议")) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(ctx, MainActivity::class.java))
                    1 -> startActivity(Intent(ctx, MeetingActivity::class.java))
                }
            }
            .show()
    }
}
