package cz.galaxyai.lsposed.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

class ModelProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "cz.galaxyai.lsposed.modelprovider"
        private const val TAG = "GalaxyAiCzechHook"
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val relPath = uri.path?.removePrefix("/") ?: throw FileNotFoundException("Invalid URI: $uri")

        // Konfigurace pro procesy bez přístupu k /storage (viz ModuleConfig.PROVIDER_URI).
        if (relPath == "config" || relPath == "config.json") {
            val candidates = listOf(
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

        val baseDir = File("/storage/emulated/0/GalaxyAI/models")
        val targetFile = File(baseDir, relPath)

        if (targetFile.exists() && targetFile.isFile) {
            Log.i(TAG, "[ModelProvider] Poskytuji deskriptor pro soubor: ${targetFile.absolutePath} (${targetFile.length()} bytů)")
            return ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val altDir = File("/sdcard/GalaxyAI/models")
        val altFile = File(altDir, relPath)
        if (altFile.exists() && altFile.isFile) {
            Log.i(TAG, "[ModelProvider] Poskytuji deskriptor pro soubor: ${altFile.absolutePath} (${altFile.length()} bytů)")
            return ParcelFileDescriptor.open(altFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        Log.e(TAG, "[ModelProvider] Soubor nenalezen: $relPath (hledáno v ${targetFile.absolutePath})")
        throw FileNotFoundException("Model file not found: $relPath")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment == "config") "application/json" else "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
