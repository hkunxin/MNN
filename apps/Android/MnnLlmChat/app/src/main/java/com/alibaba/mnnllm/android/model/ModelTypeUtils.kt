// Created by ruoyi.sjd on 2024/12/25.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.model

import java.util.Locale
import com.alibaba.mnnllm.android.modelist.ModelListManager

object ModelTypeUtils {

    // R1: cache for config.json audio_type checks (path -> hasAudioType) to avoid repeated IO
    private val audioConfigCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun isAudioModel(modelId: String): Boolean {
        if (modelId.lowercase(Locale.getDefault()).contains("audio") || isOmni(modelId)
            || ModelListManager.isAudioModel(modelId)) {
            return true
        }
        // R1: fallback to config.json audio_type detection (e.g. locally imported models
        // whose directory name does not contain "audio"/"omni")
        return hasAudioTypeConfig(modelId)
    }

    /**
     * R1: resolve the model directory from a modelId and check whether its config.json
     * declares audio capability via the "audio_type" field (written by llmexport for
     * audio/omni models such as Qwen3-ASR).
     */
    fun hasAudioTypeConfig(modelId: String): Boolean {
        val modelDirPath = resolveModelDirPath(modelId) ?: return false
        audioConfigCache[modelDirPath]?.let { return it }
        val result = try {
            val configFile = java.io.File(modelDirPath, "config.json")
            if (configFile.exists()) {
                val json = org.json.JSONObject(configFile.readText())
                json.has("audio_type")
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("ModelTypeUtils", "hasAudioTypeConfig: failed to read config for $modelId", e)
            false
        }
        audioConfigCache[modelDirPath] = result
        return result
    }

    private fun resolveModelDirPath(modelId: String): String? {
        return if (modelId.startsWith("local/")) {
            modelId.removePrefix("local/")
        } else {
            try {
                com.alibaba.mls.api.download.ModelDownloadManager
                    .getInstance(com.alibaba.mls.api.ApplicationProvider.get())
                    .getDownloadedFile(modelId)?.absolutePath
            } catch (e: Exception) {
                null
            }
        }
    }

    fun clearAudioConfigCache(modelDirPath: String? = null) {
        if (modelDirPath == null) {
            audioConfigCache.clear()
        } else {
            audioConfigCache.remove(modelDirPath)
        }
    }

    fun isMultiModalModel(modelName: String): Boolean {
        return isAudioModel(modelName) || isVisualModel(modelName) || isDiffusionModel(modelName) || isOmni(modelName)
    }

    fun isQnnModel(modelId: String): Boolean {
        val normalizedId = modelId.lowercase(Locale.getDefault())
        if (modelId.startsWith("local/") && normalizedId.contains("qnn")) {
            return true
        }
        val tags = ModelListManager.getModelTags(modelId)
        if (isQnnModel(tags)) {
            return true
        }
        return false;
    }

    fun isDiffusionModel(modelName: String): Boolean {
        val lower = modelName.lowercase(Locale.getDefault())
        return lower.contains("stable-diffusion") || lower.contains("sana")
    }

    fun isSanaModel(modelName: String): Boolean {
        return modelName.lowercase(Locale.getDefault()).contains("sana")
    }

    fun requiresFaceImageInput(modelName: String): Boolean {
        return isSanaModel(modelName)
    }

    fun isVisualModel(modelId: String): Boolean {
        return modelId.lowercase(Locale.getDefault()).contains("vl") || isOmni(modelId) ||
                ModelListManager.isVisualModel(modelId) || isSanaModel(modelId)
    }

    fun isVideoModel(modelId: String): Boolean {
        return modelId.lowercase(Locale.getDefault()).contains("video") ||
                ModelListManager.isVideoModel(modelId)
    }

    fun isR1Model(modelName: String): Boolean {
        return modelName.lowercase(Locale.getDefault()).contains("deepseek-r1")
    }

    fun isOmni(modelName: String): Boolean {
        return modelName.lowercase(Locale.getDefault()).contains("omni")
    }

    fun isSupportThinkingSwitchByTags(extraTags: List<String>): Boolean {
        return extraTags.any { it.equals("ThinkingSwitch", ignoreCase = true) }
    }

    fun isOpenClWarningByExtraTags(extraTags: List<String>): Boolean {
        return extraTags.any {
            it.equals("opencl_warning", ignoreCase = true) ||
                it.equals("OpenCLWarning", ignoreCase = true)
        }
    }

    fun isQnnModel(tags: List<String>): Boolean {
        return tags.any { it.equals("QNN", ignoreCase = true) }
    }

    fun supportAudioOutput(modelName: String): Boolean {
        return isOmni(modelName)
    }

    /**
     * Check if the model is a TTS (Text-to-Speech) model
     */
    fun isTtsModel(modelName: String): Boolean {
        return modelName.lowercase(Locale.getDefault()).contains("bert-vits") ||
               modelName.lowercase(Locale.getDefault()).contains("tts")
    }

    /**
     * Check if the model is a TTS model based on tags
     */
    fun isTtsModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("TTS", ignoreCase = true) }
    }

    /**
     * Check if the model is an ASR (Automatic Speech Recognition) model based on tags
     */
    fun isAsrModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("ASR", ignoreCase = true) }
    }

    /**
     * Check if the model is a thinking model based on tags
     */
    fun isThinkingModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("Think", ignoreCase = true) }
    }

    /**
     * Check if the model is a visual model based on tags
     */
    fun isVisualModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("Vision", ignoreCase = true) }
    }

    /**
     * Check if the model is a video model based on tags
     */
    fun isVideoModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("Video", ignoreCase = true) }
    }

    /**
     * Check if the model is an audio model based on tags
     */
    fun isAudioModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("Audio", ignoreCase = true) }
    }
}
