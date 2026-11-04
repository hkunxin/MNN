// Spec 005 task 2: 新建对话统一入口。
//
// 背景：ChatActivity 要求 intent 带 modelId + modelName，缺失直接 finish()；
// 旧实现「新建对话」= 打开模型列表页（MainActivity），于是必须先选模型。
// 这里把「用哪个模型」收敛到 DefaultChatModelStore，点击即直达对话页。
package com.alibaba.mnnllm.android.chat

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.main.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.atomic.AtomicBoolean

object NewChatLauncher {

    private const val TAG = "NewChatLauncher"

    /** 防连点：解析模型有磁盘/DB 访问，双击不应该开出两个对话页。 */
    private val opening = AtomicBoolean(false)

    /**
     * 打开一个全新的对话页，模型按 R3/R4 规则解析。
     * 解析放后台线程：可用模型枚举会碰 SQLite 与目录扫描，不该卡住点击反馈。
     */
    fun openNewChat(context: Context) {
        if (!opening.compareAndSet(false, true)) {
            Log.d(TAG, "openNewChat ignored — already opening")
            return
        }
        val appContext = context.applicationContext
        val ui = Handler(Looper.getMainLooper())
        Thread({
            val resolution = try {
                DefaultChatModelStore.resolve(appContext)
            } catch (t: Throwable) {
                Log.w(TAG, "resolve default model failed", t)
                DefaultChatModelStore.Resolution.Empty
            }
            ui.post {
                opening.set(false)
                when (resolution) {
                    is DefaultChatModelStore.Resolution.Ready -> {
                        Log.i(
                            TAG,
                            "new chat model=${resolution.modelId} fromDefault=${resolution.fromDefault}"
                        )
                        ChatRouter.startRun(appContext, resolution.modelId, null, null)
                    }
                    DefaultChatModelStore.Resolution.Empty -> promptNoModel(appContext)
                }
            }
        }, "new-chat-resolve").start()
    }

    /** 没有可用模型时的引导：先解释，再送到模型管理（就是原来的模型列表页）。 */
    private fun promptNoModel(context: Context) {
        val openModelManager = {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
        runCatching {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.no_chat_model_title)
                .setMessage(R.string.no_chat_model_message)
                .setPositiveButton(R.string.no_chat_model_go) { _, _ -> openModelManager() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }.onFailure {
            // 非 Activity 上下文（如前台服务）创建不了对话框，退化为 Toast 提示。
            Toast.makeText(context, R.string.no_chat_model_message, Toast.LENGTH_LONG).show()
        }
    }
}
