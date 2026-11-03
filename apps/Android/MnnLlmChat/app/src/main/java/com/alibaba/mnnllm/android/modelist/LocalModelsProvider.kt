package com.alibaba.mnnllm.android.modelist

import android.util.Log
import com.alibaba.mls.api.ApplicationProvider
import com.alibaba.mls.api.ModelItem
import java.io.File

object LocalModelsProvider {

    /**
     * Collects local models from:
     * 1. the adb debug directory /data/local/tmp/mnn_models/ (each subdirectory),
     * 2. user-imported model directories persisted by [ModelImporter] (R2).
     * A directory qualifies as a model when it contains a config.json.
     */
    fun getLocalModels(): MutableList<ModelItem> {
        val result = mutableListOf<ModelItem>()
        val seen = mutableSetOf<String>()
        fun addModelDir(dir: File) {
            try {
                if (!dir.isDirectory || !File(dir, "config.json").exists()) return
                val canonical = try { dir.canonicalPath } catch (e: Exception) { dir.absolutePath }
                if (!seen.add(canonical)) return
                val modelPath = dir.absolutePath
                val modelId = "local/${modelPath}"
                result.add(ModelItem.fromLocalModel(modelId, modelPath))
            } catch (e: Exception) {
                Log.e("LocalModelsProvider", "Failed to add model dir ${dir.absolutePath}", e)
            }
        }
        try {
            val modelsDir = File("/data/local/tmp/mnn_models/")
            if (modelsDir.exists() && modelsDir.isDirectory) {
                modelsDir.listFiles()?.forEach { modelDir ->
                    addModelDir(modelDir)
                }
            }
        } catch (e: Exception) {
            Log.e("LocalModelsProvider", "Failed to load models from /data/local/tmp/mnn_models/", e)
        }
        try {
            ModelImporter.getImportedModelPaths(ApplicationProvider.get()).forEach { path ->
                addModelDir(File(path))
            }
        } catch (e: Exception) {
            Log.e("LocalModelsProvider", "Failed to load imported models", e)
        }
        return result
    }
}
