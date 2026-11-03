// Created for Spec 001 (R2): local model import via directory scan.
package com.alibaba.mnnllm.android.modelist

import android.content.Context
import android.os.Environment
import android.util.Log
import com.alibaba.mnnllm.android.utils.PreferenceUtils
import java.io.File

/**
 * R2: scans well-known directories for locally converted MNN models (directories that
 * contain a valid config.json) and persists the user-imported model paths so that
 * [LocalModelsProvider] can expose them in the model list.
 */
object ModelImporter {

    private const val TAG = "ModelImporter"
    private const val PREF_KEY_IMPORTED_PATHS = "imported_local_model_paths"

    /** Default scan root for adb-accessible storage (R2 requirement). */
    const val DEFAULT_SCAN_DIR = "mnn-models"

    /** Debug scan root kept for adb workflows (existing LocalModelsProvider behavior). */
    const val DEBUG_SCAN_DIR = "/data/local/tmp/mnn_models"

    data class Candidate(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val valid: Boolean,
        val invalidReason: String?,
        val alreadyImported: Boolean
    )

    fun getImportedModelPaths(context: Context?): List<String> {
        if (context == null) return emptyList()
        val saved = PreferenceUtils.getString(context, PREF_KEY_IMPORTED_PATHS, null) ?: return emptyList()
        return saved.split("\n").filter { it.isNotBlank() }
    }

    fun addImportedModelPath(context: Context, path: String) {
        val paths = getImportedModelPaths(context).toMutableSet()
        paths.add(path)
        PreferenceUtils.setString(context, PREF_KEY_IMPORTED_PATHS, paths.joinToString("\n"))
        Log.d(TAG, "addImportedModelPath: $path")
    }

    fun removeImportedModelPath(context: Context, path: String) {
        val paths = getImportedModelPaths(context).toMutableSet()
        paths.remove(path)
        PreferenceUtils.setString(context, PREF_KEY_IMPORTED_PATHS, paths.joinToString("\n"))
        Log.d(TAG, "removeImportedModelPath: $path")
    }

    fun isValidModelDir(dir: File): Boolean {
        return dir.isDirectory && File(dir, "config.json").exists()
    }

    /**
     * Scan all well-known roots plus previously imported paths and build the candidate
     * list for the import dialog. Invalid directories are reported with a reason and
     * cannot be imported.
     */
    fun scanCandidates(context: Context): List<Candidate> {
        val imported = getImportedModelPaths(context)
            .map { normalizePath(it) }
            .toSet()
        val seen = mutableSetOf<String>()
        val candidates = mutableListOf<Candidate>()

        val roots = listOfNotNull(
            File(Environment.getExternalStorageDirectory(), DEFAULT_SCAN_DIR),
            File(DEBUG_SCAN_DIR)
        )
        for (root in roots) {
            val subDirs = try {
                root.listFiles()?.filter { it.isDirectory } ?: continue
            } catch (e: Exception) {
                Log.w(TAG, "scanCandidates: cannot list ${root.absolutePath}", e)
                continue
            }
            for (dir in subDirs.sortedBy { it.name.lowercase() }) {
                addCandidate(candidates, seen, dir, imported)
            }
        }
        // Previously imported dirs from arbitrary locations (e.g. copied into app storage)
        for (path in imported) {
            val dir = File(path)
            if (dir.exists()) {
                addCandidate(candidates, seen, dir, imported)
            }
        }
        return candidates.sortedBy { it.name.lowercase() }
    }

    private fun addCandidate(
        candidates: MutableList<Candidate>,
        seen: MutableSet<String>,
        dir: File,
        imported: Set<String>
    ) {
        val path = normalizePath(dir.absolutePath)
        if (!seen.add(path)) return
        val valid = isValidModelDir(dir)
        val invalidReason = when {
            valid -> null
            !File(dir, "config.json").exists() -> "missing config.json"
            else -> "config.json unreadable"
        }
        val size = if (valid) {
            try {
                dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } catch (e: Exception) {
                0L
            }
        } else 0L
        candidates.add(
            Candidate(
                name = dir.name,
                path = path,
                sizeBytes = size,
                valid = valid,
                invalidReason = invalidReason,
                alreadyImported = path in imported
            )
        )
    }

    private fun normalizePath(path: String): String {
        return try {
            File(path).canonicalPath
        } catch (e: Exception) {
            path
        }
    }
}
