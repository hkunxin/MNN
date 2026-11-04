// 通知使用权：授予判定 + 失联自愈（移植自 mnblocker NotificationAccessUtils）。
//
// 为什么需要这个类（真机实证，mnblocker 同源注释）：
//   - 「设置里勾着通知使用权」≠「系统已经把 listener 绑起来」。覆盖安装 APK 会掉绑定
//     而设置里的授权状态保留，此后监听器一条通知都收不到，直到下次重启或用户手动
//     关/开一次开关。CI 每轮推包都要重装一次，这个死状态几乎是必现的。
//   - NotificationListenerService.requestRebind 单独用不够：它走
//     ManagedServices.setComponentState(component,user,true)，组件不在 snooze 状态时
//     直接 early-return，只能撤销显式 requestUnbind，救不了系统自己丢的绑定。
//   - 真正有效的是 cycle 自己组件的 enabled 状态：NotificationManagerService 对包变更
//     的反应是反注册再重注册服务，DONT_KILL_APP 保证调用进程不死。
//   - 授权状态只能读 Settings.Secure 的 enabled_notification_listeners（公开字段，
//     compileSdk 的 android.jar 里没有该常量，故用字面量 key）。
package com.alibaba.mnnllm.android.assistant.notify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.util.Log

object NotificationAccess {

    private const val TAG = "SmartNotify"

    /** Settings.Secure key；@SystemApi 的常量在 android.jar 里不可见，用字面量。 */
    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"

    private const val PREFS = "notify_access"
    private const val KEY_LAST_REBIND_AT = "last_rebind_at"

    /** 两次自愈尝试的最小间隔，避免 onResume 抖动把系统反复 cycle。 */
    private const val REBIND_THROTTLE_MS = 15_000L

    private fun component(ctx: Context) =
        ComponentName(ctx.applicationContext, SmartNotificationListener::class.java)

    /** 用户在系统设置里是否勾选了本应用的通知使用权（不代表系统已绑定）。 */
    @JvmStatic
    fun isGranted(ctx: Context): Boolean {
        return try {
            val flat = Settings.Secure.getString(
                ctx.contentResolver, ENABLED_NOTIFICATION_LISTENERS)
            if (TextUtils.isEmpty(flat)) {
                return false
            }
            val pkg = ctx.packageName
            flat.split(":").any { name ->
                val cn = ComponentName.unflattenFromString(name)
                cn != null && pkg == cn.packageName
            }
        } catch (t: Throwable) {
            Log.w(TAG, "isGranted failed", t)
            false
        }
    }

    /** 跳到系统的「通知使用权」页面；ROM 没有该页面时不崩。 */
    @JvmStatic
    fun openSettings(ctx: Context) {
        try {
            val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.applicationContext.startActivity(i)
        } catch (t: Throwable) {
            Log.w(TAG, "openSettings failed — ROM has no notification-access page", t)
        }
    }

    /**
     * 已授权但未连接时，强制系统重新绑定本 listener。
     *
     * @return true 表示本次真的发起了一次自愈（供 UI 提示），false 表示不需要或
     *         被节流掉了。
     */
    @JvmStatic
    fun ensureBound(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val state = when {
            !isGranted(app) -> "not-granted"
            SmartNotificationListener.isConnected() -> "already-connected"
            else -> null
        }
        if (state != null) {
            return false
        }

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_REBIND_AT, 0L) < REBIND_THROTTLE_MS) {
            return false
        }
        prefs.edit().putLong(KEY_LAST_REBIND_AT, now).apply()

        val cn = component(app)
        // 先试便宜的 requestRebind（能救「被 requestUnbind 挂起」的场景）。
        try {
            NotificationListenerService.requestRebind(cn)
        } catch (t: Throwable) {
            Log.w(TAG, "requestRebind failed", t)
        }
        // 再 cycle 组件状态，救系统自己丢掉的绑定。
        try {
            val pm = app.packageManager
            pm.setComponentEnabledSetting(
                cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(
                cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP)
            Log.i(TAG, "listener granted but unbound — component cycled to force rebind")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "component cycle failed (restricted settings?)", t)
            return false
        }
    }
}
