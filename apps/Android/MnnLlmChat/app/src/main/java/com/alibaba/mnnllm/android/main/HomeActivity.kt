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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.assistant.notify.AssistantTabFragment
import com.alibaba.mnnllm.android.mainsettings.MainSettingsFragment
import com.alibaba.mnnllm.android.utils.SystemBarInsets

class HomeActivity : AppCompatActivity() {

    private lateinit var tabs: List<Pair<LinearLayout, Pair<TextView, TextView>>>

    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        applySystemBarInsets()
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

    /**
     * targetSdk 35 起 Android 15 强制 edge-to-edge，三 Tab 内容会直接画进状态栏/导航栏区域。
     * 统一在壳子层吃掉系统栏 inset：
     *  - 状态栏高度 → 内容区 paddingTop（appbar 下移，不再被状态栏压住）
     *  - 导航栏高度 → 底栏增高 + 底部内边距（白底延伸到手势条下方，Tab 文案不被挡）
     *  - 刘海/挖孔/横屏 → 根布局左右内边距
     * 各 Tab fragment 自身不再处理 inset，避免二次叠加。
     */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.homeRoot)
        val content = findViewById<View>(R.id.homeContainer)
        val bottomBar = findViewById<View>(R.id.homeBottomBar)
        val barBaseHeight = bottomBar.layoutParams.height
        val barBasePaddingBottom = bottomBar.paddingBottom

        // edge-to-edge + 系统栏图标明暗统一走工具类，避免和二级页各写一份
        SystemBarInsets.enableEdgeToEdge(this, root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            root.updatePadding(left = bars.left, right = bars.right)
            content.updatePadding(top = bars.top)
            bottomBar.layoutParams = bottomBar.layoutParams.apply {
                height = barBaseHeight + bars.bottom
            }
            bottomBar.updatePadding(bottom = barBasePaddingBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
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
