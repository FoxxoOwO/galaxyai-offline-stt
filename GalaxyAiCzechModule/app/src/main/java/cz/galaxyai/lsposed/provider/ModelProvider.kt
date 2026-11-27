package cz.galaxyai.lsposed.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import cz.galaxyai.lsposed.config.ModelEngine
import cz.galaxyai.lsposed.config.ModuleConfig
import java.io.File
import java.io.FileNotFoundException

class ModelProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "cz.galaxyai.lsposed.modelprovider"
        private const val TAG = "GalaxyAiCzechHook"

        internal val ALLOWED_CALLING_PACKAGES = setOf(
            "cz.galaxyai.lsposed",
            "com.samsung.android.intellivoiceservice",
            "com.sec.android.app.voicenote",
            "com.samsung.android.app.notes"
        )

        internal fun isAllowedCallerPackage(pkg: String?): Boolean =
            pkg != null && pkg in ALLOWED_CALLING_PACKAGES

        internal fun isAllowedRelativePath(relPath: String?): Boolean {
            if (relPath.isNullOrBlank()) return false
            if (relPath != relPath.trim()) return false
            if (relPath.startsWith("/") || relPath.contains('\\') || relPath.contains("..") || relPath.contains('\u0000')) {
                return false
            }
            if (relPath == "config" || relPath == "config.json") return true
            if (relPath == "speaker/segmentation.onnx" || relPath == "speaker/embedding.onnx") return true

            val parts = relPath.split('/')
            if (parts.size != 2) return false
            val dir = parts[0]
            val fileName = parts[1]
            if (!dir.startsWith("whisper-")) return false
            val modelId = dir.removePrefix("whisper-")
            val model = ModuleConfig.getModelById(modelId) ?: return false

            return when (model.engine) {
                ModelEngine.CZECH_SPEECH_GGML -> fileName == model.ggmlFileName
                ModelEngine.SHERPA_ONNX -> fileName == "${model.id}-encoder.int8.onnx" ||
                    fileName == "${model.id}-decoder.int8.onnx" ||
                    fileName == "${model.id}-tokens.txt"
            }
        }

        internal fun resolveSafeFile(baseDir: File, relPath: String): File? {
            if (!isAllowedRelativePath(relPath)) return null
            val canonicalBase = baseDir.canonicalFile
            val target = File(canonicalBase, relPath).canonicalFile
            val prefix = canonicalBase.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
            if (!target.path.startsWith(prefix)) return null
            return if (target.exists() && target.isFile) target else null
        }
    }

    override fun onCreate(): Boolean = true

    private fun enforceCallerAuthorized() {
        val pkg = callingPackage
        if (isAllowedCallerPackage(pkg)) return

        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid()) return

        val packages = context?.packageManager?.getPackagesForUid(callingUid)
        if (packages != null && packages.any { isAllowedCallerPackage(it) }) return

        Log.w(TAG, "[ModelProvider] Zamítnut neoprávněný přístup (pkg=$pkg, uid=$callingUid)")
        throw SecurityException("Unauthorized caller")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") {
            throw SecurityException("Only read mode is supported")
        }
        enforceCallerAuthorized()

        val relPath = uri.path?.removePrefix("/")
        if (!isAllowedRelativePath(relPath) || relPath == null) {
            Log.w(TAG, "[ModelProvider] Zamítnuta nepovolená cesta: ${uri.path}")
            throw SecurityException("Disallowed path")
        }

        // Konfigurace pro procesy bez přístupu k /storage (viz ModuleConfig.PROVIDER_URI).
        if (relPath == "config" || relPath == "config.json") {
            val candidates = listOf(
                "/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI/config.json",
                "/storage/emulated/0/GalaxyAI/config.json",
                "/sdcard/GalaxyAI/config.json"
            )
            for (path in candidates) {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    Log.i(TAG, "[ModelProvider] Poskytuji config: ${f.absolutePath} (${f.length()} bytů)")
                    return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            }
            context?.let { ctx ->
                val f = File(ctx.filesDir, "config.json")
                if (f.exists() && f.canRead()) {
                    Log.i(TAG, "[ModelProvider] Poskytuji config z privátního úložiště (${f.length()} bytů)")
                    return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            }
            Log.e(TAG, "[ModelProvider] Config nenalezen v žádné cestě")
            throw FileNotFoundException("Config not found")
        }

        val baseDirs = if (relPath.startsWith("speaker/")) {
            listOf(
                File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI/models"),
                File("/storage/emulated/0/GalaxyAI/models"),
                File("/sdcard/GalaxyAI/models")
            )
        } else {
            listOf(
                File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models"),
                File("/storage/emulated/0/GalaxyAI/models"),
                File("/sdcard/GalaxyAI/models")
            )
        }

        for (baseDir in baseDirs) {
            val resolved = resolveSafeFile(baseDir, relPath)
            if (resolved != null) {
                Log.i(TAG, "[ModelProvider] Poskytuji deskriptor pro soubor: ${resolved.name} (${resolved.length()} bytů)")
                return ParcelFileDescriptor.open(resolved, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }

        Log.e(TAG, "[ModelProvider] Soubor nenalezen: $relPath")
        throw FileNotFoundException("Model file not found: $relPath")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment == "config") "application/json" else "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
