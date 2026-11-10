package cz.galaxyai.lsposed.ui

import android.util.Log
import cz.galaxyai.lsposed.MainHook.Companion.TAG
import cz.galaxyai.lsposed.config.ModelInfo
import cz.galaxyai.lsposed.config.ModuleConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private data class DownloadItem(val filename: String, val url: String)

object ModelDownloader {

    suspend fun downloadModel(
        model: ModelInfo,
        onProgress: (percent: Int, status: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File("/storage/emulated/0/GalaxyAI/models/whisper-${model.id}")
        val mediaDir = File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/whisper-${model.id}")
        targetDir.mkdirs()
        try { mediaDir.mkdirs() } catch (_: Throwable) {}

        val filesToDownload = listOf(
            DownloadItem("${model.id}-tokens.txt", model.tokensUrl),
            DownloadItem("${model.id}-encoder.int8.onnx", model.encoderUrl),
            DownloadItem("${model.id}-decoder.int8.onnx", model.decoderUrl)
        )

        try {
            var totalDownloadedAll = 0L
            val totalSizeApprox = model.sizeMb * 1024L * 1024L

            for (item in filesToDownload) {
                val filename = item.filename
                val urlStr = item.url
                val destFile = File(targetDir, filename)
                val tempFile = File(targetDir, "$filename.download")

                onProgress(
                    ((totalDownloadedAll * 100) / totalSizeApprox).toInt().coerceIn(0, 99),
                    "Downloading $filename..."
                )

                downloadFileWithRedirects(urlStr, tempFile) { bytesRead ->
                    totalDownloadedAll += bytesRead
                    val percent = ((totalDownloadedAll * 100) / totalSizeApprox).toInt().coerceIn(0, 99)
                    val mb = totalDownloadedAll / (1024 * 1024)
                    onProgress(percent, "Downloading: $mb MB / ~${model.sizeMb} MB ($filename)")
                }

                tempFile.renameTo(destFile)
                destFile.setReadable(true, false)
                destFile.setWritable(true, false)

                // Mirror to media directory immediately
                try {
                    val mediaFile = File(mediaDir, filename)
                    destFile.copyTo(mediaFile, overwrite = true)
                    mediaFile.setReadable(true, false)
                    mediaFile.setWritable(true, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "[Downloader] Mirroring $filename to media dir failed: ${t.message}")
                }
            }

            targetDir.setReadable(true, false)
            targetDir.setWritable(true, false)
            targetDir.setExecutable(true, false)

            onProgress(100, "Model ${model.name} prepared successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[Downloader] Error: ${e.message}", e)
            onProgress(0, "Download error: ${e.message}")
            false
        }
    }

    suspend fun downloadDiarizationModel(
        onProgress: (percent: Int, status: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val diarizationInfo = ModuleConfig.DIARIZATION_MODEL
        val targetDir = File("/storage/emulated/0/GalaxyAI/models/speaker")
        val mediaDir = File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI/models/speaker")
        targetDir.mkdirs()
        try { mediaDir.mkdirs() } catch (_: Throwable) {}

        val filesToDownload = listOf(
            DownloadItem("segmentation.onnx", diarizationInfo.segmentationUrl),
            DownloadItem("embedding.onnx", diarizationInfo.embeddingUrl)
        )

        try {
            var totalDownloadedAll = 0L
            val totalSizeApprox = diarizationInfo.sizeMb * 1024L * 1024L

            for (item in filesToDownload) {
                val filename = item.filename
                val urlStr = item.url
                val destFile = File(targetDir, filename)
                val tempFile = File(targetDir, "$filename.download")

                onProgress(
                    ((totalDownloadedAll * 100) / totalSizeApprox).toInt().coerceIn(0, 99),
                    "Downloading $filename..."
                )

                downloadFileWithRedirects(urlStr, tempFile) { bytesRead ->
                    totalDownloadedAll += bytesRead
                    val percent = ((totalDownloadedAll * 100) / totalSizeApprox).toInt().coerceIn(0, 99)
                    val mb = totalDownloadedAll / (1024 * 1024)
                    onProgress(percent, "Downloading: $mb MB / ~${diarizationInfo.sizeMb} MB ($filename)")
                }

                tempFile.renameTo(destFile)
                destFile.setReadable(true, false)
                destFile.setWritable(true, false)

                // Mirror to media directory
                try {
                    val mediaFile = File(mediaDir, filename)
                    destFile.copyTo(mediaFile, overwrite = true)
                    mediaFile.setReadable(true, false)
                    mediaFile.setWritable(true, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "[Downloader] Mirroring $filename to media dir failed: ${t.message}")
                }
            }

            targetDir.setReadable(true, false)
            targetDir.setWritable(true, false)

            onProgress(100, "Speaker Diarization Pack prepared successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[Downloader] Diarization download error: ${e.message}", e)
            onProgress(0, "Download error: ${e.message}")
            false
        }
    }

    suspend fun deleteModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val userDir = File("/storage/emulated/0/GalaxyAI/models/whisper-$modelId")
            val mediaDir = File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/whisper-$modelId")
            if (userDir.exists()) userDir.deleteRecursively()
            if (mediaDir.exists()) mediaDir.deleteRecursively()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "[Downloader] Delete model failed: ${t.message}")
            false
        }
    }

    suspend fun deleteDiarization(): Boolean = withContext(Dispatchers.IO) {
        try {
            val userDir = File("/storage/emulated/0/GalaxyAI/models/speaker")
            val mediaDir = File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI/models/speaker")
            if (userDir.exists()) userDir.deleteRecursively()
            if (mediaDir.exists()) mediaDir.deleteRecursively()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "[Downloader] Delete diarization failed: ${t.message}")
            false
        }
    }

    private fun downloadFileWithRedirects(
        urlStr: String,
        destFile: File,
        onBytesRead: (Int) -> Unit
    ) {
        var currentUrl = urlStr
        var redirects = 0
        var conn: HttpURLConnection? = null

        while (redirects < 7) {
            val url = URL(currentUrl)
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20000
            conn.readTimeout = 40000
            val code = conn.responseCode
            if (code in 301..308) {
                currentUrl = conn.getHeaderField("Location")
                redirects++
                conn.disconnect()
            } else {
                break
            }
        }

        if (conn == null || conn.responseCode != 200) {
            throw IllegalStateException("HTTP ${conn?.responseCode} error while downloading from $currentUrl")
        }

        conn.inputStream.use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    onBytesRead(bytesRead)
                }
                output.flush()
            }
        }
        conn.disconnect()
    }
}
