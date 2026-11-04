// V2.1 Tab1 对话：会话列表（复用 ChatHistoryFragment）+ 新对话（跳模型管理选择）
// + 会议记录入口（V2.1 定稿：会议记录入口在 Tab1，进行页不计 Tab）。
package com.alibaba.mnnllm.android.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.assistant.notify.meeting.MeetingActivity
import com.alibaba.mnnllm.android.history.ChatHistoryFragment

class ChatTabFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_chat_tab, container, false)
        view.findViewById<Button>(R.id.btnNewChat).setOnClickListener {
            // V2.1：模型选择收进「我的」二级；新对话从这里跳模型管理选模型开始。
            startActivity(Intent(requireContext(), MainActivity::class.java))
        }
        view.findViewById<Button>(R.id.btnMeetingEntry).setOnClickListener {
            startActivity(Intent(requireContext(), MeetingActivity::class.java))
        }
        // 会话列表
        childFragmentManager.beginTransaction()
            .replace(R.id.chatListContainer, ChatHistoryFragment())
            .commit()
        return view
    }
}
