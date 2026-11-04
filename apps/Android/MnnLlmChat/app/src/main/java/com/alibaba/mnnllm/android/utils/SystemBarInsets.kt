// 系统栏适配统一入口（spec-002 V2.1）。背景：
//   targetSdk 35 → Android 15 起系统强制 edge-to-edge，窗口不再自动为状态栏/导航栏让位。
//   老页面靠布局里的 android:fitsSystemWindows="true" 侥幸生效；V2.1 新增页面全部没有该属性，
//   于是标题被状态栏压住、底部按钮被手势条盖住。所有新页面统一走这里，不要各自再写一份。
package com.alibaba.mnnllm.android.utils

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object SystemBarInsets {

    /**
     * 打开 edge-to-edge，并让系统栏图标明暗跟随日夜模式（浅色页面 → 深色图标）。
     * 只做窗口级配置，不碰任何 View 的 padding，调用方可自行决定 inset 怎么用。
     */
    fun enableEdgeToEdge(activity: Activity, root: View) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 否则手势条下方会自动加一层半透明遮罩，和页面底色对不上
            window.isNavigationBarContrastEnforced = false
        }
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    /**
     * 通用页面：把系统栏 inset 直接吃成根布局的 padding。
     *  - top/left/right ← 状态栏 + 刘海/挖孔（标题不再被压）
     *  - bottom ← max(导航栏, 输入法)（decorFits=false 后 adjustResize 失效，必须自己吃 ime）
     * padding 打在「内容根」上而不是 android.R.id.content 上：根布局自带页面底色，
     * padding 区域同样会被背景覆盖，状态栏那一条不会露出白底。
     */
    fun applyPadding(activity: Activity) {
        val root = contentRootOf(activity) ?: return
        enableEdgeToEdge(activity, root)
        // 布局自带的 padding 要保留，inset 是叠加而不是覆盖
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + maxOf(bars.bottom, ime)
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** setContentView 塞进 android.R.id.content 的第一个子 View 即布局根；异常情况退回 content 本身。 */
    private fun contentRootOf(activity: Activity): View? {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        if (content.childCount == 0) return content
        return content.getChildAt(0) ?: content
    }
}
