// Spec 005 task 4: 记忆条目模型。
//
// 用 String 常量而不是 enum 存 source：JSON 落盘后跨版本读回不会被枚举改名影响。
package com.alibaba.mnnllm.android.memory

data class MemoryItem(
    val id: String,
    var content: String,
    var tags: List<String> = emptyList(),
    /** [SOURCE_MANUAL] 用户手写；[SOURCE_AUTO] 从对话中自动抽取。 */
    var source: String = SOURCE_MANUAL,
    var enabled: Boolean = true,
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L
) {
    val isManual: Boolean get() = source == SOURCE_MANUAL

    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_AUTO = "auto"
    }
}
