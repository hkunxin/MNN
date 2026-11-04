// V2.1 定稿 IA（2026-11-04 用户审核通过）：三 Tab 主框架。
//   Tab1 对话：会话列表 → 对话页（会议记录入口在 Tab1，不计 Tab）
//   Tab2 助理：通知摘要 → 规则建议 / 拦截动态 / 助理设置
//   Tab3 我的：设置主页（模型管理收进二级：设置里「模型管理」行 → 旧模型列表页）
package com.alibaba.mnnllm.android.main

import android.os.Bundle
import android.graphics.Typeface
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.assistant.notify.AssistantTabFragment
import com.alibaba.mnnllm.android.mainsettings.MainSettingsFragment

class HomeActivity : AppCompatActivity() {

    private lateinit var tabChat: TextView
    private lateinit var tabAssistant: TextView
    private lateinit var tabMine: TextView

    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        tabChat = findViewById(R.id.tabChat)
        tabAssistant = findViewById(R.id.tabAssistant)
        tabMine = findViewById(R.id.tabMine)

        tabChat.setOnClickListener { switchTab(0) }
        tabAssistant.setOnClickListener { switchTab(1) }
        tabMine.setOnClickListener { switchTab(2) }

        if (savedInstanceState == null) {
            switchTab(0)
        } else {
            currentTab = savedInstanceState.getInt(KEY_TAB, 0)
            // FragmentManager restores fragments; just fix tab highlight.
            highlight(currentTab)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    private fun switchTab(index: Int) {
        if (index == currentTab && supportFragmentManager.findFragmentById(R.id.homeContainer) != null) {
            highlight(index)
            return
        }
        currentTab = index
        highlight(index)
        val tag = "home_tab_$index"
        val fm = supportFragmentManager
        // 隐藏其余，显示目标；不存在则创建（保留各 Tab 状态）。
        val tx = fm.beginTransaction()
        for (i in 0..2) {
            val f = fm.findFragmentByTag("home_tab_$i")
            if (f != null && i != index) tx.hide(f)
        }
        val target = fm.findFragmentByTag(tag)
        if (target != null) {
            tx.show(target)
        } else {
            tx.add(R.id.homeContainer, createFragment(index), tag)
        }
        tx.commitAllowingStateLoss()
    }

    private fun createFragment(index: Int): Fragment = when (index) {
        0 -> ChatTabFragment()
        1 -> AssistantTabFragment()
        else -> MainSettingsFragment()
    }

    private fun highlight(index: Int) {
        val active = 0xFF1565C0.toInt()
        val inactive = 0xFF888888.toInt()
        tabChat.setTextColor(if (index == 0) active else inactive)
        tabAssistant.setTextColor(if (index == 1) active else inactive)
        tabMine.setTextColor(if (index == 2) active else inactive)
        val bold = Typeface.DEFAULT_BOLD
        val normal = Typeface.DEFAULT
        tabChat.typeface = if (index == 0) bold else normal
        tabAssistant.typeface = if (index == 1) bold else normal
        tabMine.typeface = if (index == 2) bold else normal
    }

    companion object {
        private const val KEY_TAB = "home_current_tab"
    }
}
