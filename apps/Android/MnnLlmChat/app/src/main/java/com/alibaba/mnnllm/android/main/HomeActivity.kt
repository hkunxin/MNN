// V2.1 定稿 IA（2026-11-04 用户审核通过）：三 Tab 主框架。
//   Tab1 对话：会话列表 → 对话页（会议记录入口在 Tab1，不计 Tab）
//   Tab2 助理：通知摘要 → 规则建议 / 拦截动态 / 助理设置
//   Tab3 我的：设置主页（模型管理收进二级：设置里「模型管理」行 → 旧模型列表页）
// 底栏对齐设计稿：图标+文字，选中 accent #1A73E8 加粗、图标放大。
package com.alibaba.mnnllm.android.main

import android.os.Bundle
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.assistant.notify.AssistantTabFragment
import com.alibaba.mnnllm.android.mainsettings.MainSettingsFragment

class HomeActivity : AppCompatActivity() {

    private lateinit var tabs: List<Pair<LinearLayout, Pair<TextView, TextView>>>

    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        tabs = listOf(
            findViewById<LinearLayout>(R.id.tabChat) to
                (findViewById<TextView>(R.id.tabChatIcon) to findViewById<TextView>(R.id.tabChatLabel)),
            findViewById<LinearLayout>(R.id.tabAssistant) to
                (findViewById<TextView>(R.id.tabAssistantIcon) to findViewById<TextView>(R.id.tabAssistantLabel)),
            findViewById<LinearLayout>(R.id.tabMine) to
                (findViewById<TextView>(R.id.tabMineIcon) to findViewById<TextView>(R.id.tabMineLabel))
        )
        tabs.forEachIndexed { index, (bar, _) -> bar.setOnClickListener { switchTab(index) } }

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
        val active = 0xFF1A73E8.toInt()
        val inactive = 0xFF6B7280.toInt()
        tabs.forEachIndexed { i, (_, icons) ->
            val (icon, label) = icons
            val on = i == index
            label.setTextColor(if (on) active else inactive)
            label.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            icon.animate().scaleX(if (on) 1.12f else 1f).scaleY(if (on) 1.12f else 1f)
                .setDuration(150).start()
        }
    }

    companion object {
        private const val KEY_TAB = "home_current_tab"
    }
}
