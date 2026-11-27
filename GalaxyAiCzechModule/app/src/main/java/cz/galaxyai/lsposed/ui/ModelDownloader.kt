package cz.galaxyai.lsposed.ui

import android.util.Log
import cz.galaxyai.lsposed.MainHook.Companion.TAG
import cz.galaxyai.lsposed.config.ModelEngine
import cz.galaxyai.lsposed.config.ModelInfo
import cz.galaxyai.lsposed.config.ModuleConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private data class DownloadItem(
    val filename: String,
    val url: String,
    val expectedSha256: String = "",
    val expectedSizeBytes: Long = 0L
)

object ModelDownloader {

    private fun resolveWritableModelDir(primary: File, fallback: File): File {
        try {
            primary.mkdirs()
            if (primary.exists() && primary.isDirectory) {
                val probe = File(primary, ".write_test")
                probe.writeText("ok")
                probe.delete()
                return primary
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[Downloader] Primary dir not writable (${primary.absolutePath}): ${t.message}")
        }
        fallback.mkdirs()
        return fallback
    }

    private fun replaceFileAtomically(tempFile: File, destFile: File) {
        if (destFile.exists()) {
            destFile.delete()
        }
        if (!tempFile.renameTo(destFile)) {
            tempFile.copyTo(destFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun downloadAndVerifyItems(
        items: List<DownloadItem>,
        targetDir: File,
        totalSizeApprox: Long,
        onProgress: (percent: Int, status: String) -> Unit
    ) {
        var totalDownloadedAll = 0L
        val safeTotal = totalSizeApprox.coerceAtLeast(1L)
        val totalMbDisplay = (safeTotal / (1024L * 1024L)).coerceAtLeast(1L)

        for (item in items) {
            val filename = item.filename
            val destFile = File(targetDir, filename)
            val tempFile = File(targetDir, "$filename.download")

            onProgress(
                ((totalDownloadedAll * 100) / safeTotal).toInt().coerceIn(0, 99),
                "Downloading $filename..."
            )

            val actualSha256 = downloadFileWithRedirects(item.url, tempFile) { bytesRead ->
                totalDownloadedAll += bytesRead
                val percent = ((totalDownloadedAll * 100) / safeTotal).toInt().coerceIn(0, 99)
                val mb = totalDownloadedAll / (1024L * 1024L)
                onProgress(percent, "Downloading: $mb MB / ~$totalMbDisplay MB ($filename)")
            }

            val actualSize = tempFile.length()
            if (item.expectedSizeBytes > 0L && actualSize != item.expectedSizeBytes) {
                tempFile.delete()
                throw IllegalStateException(
                    "Size mismatch for $filename: expected ${item.expectedSizeBytes}, got $actualSize"
                )
            }
            if (item.expectedSha256.isNotBlank() && !actualSha256.equals(item.expectedSha256, ignoreCase = true)) {
                tempFile.delete()
                throw IllegalStateException("SHA-256 verification failed for $filename")
            }

            replaceFileAtomically(tempFile, destFile)
        }
    }

    suspend fun downloadModel(
        model: ModelInfo,
        onProgress: (percent: Int, status: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val mediaDir = File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/whisper-${model.id}")
        val fallbackDir = File("/storage/emulated/0/GalaxyAI/models/whisper-${model.id}")
        val targetDir = resolveWritableModelDir(mediaDir, fallbackDir)

        val filesToDownload = when (model.engine) {
            ModelEngine.CZECH_SPEECH_GGML -> listOf(
                DownloadItem(
                    filename = model.ggmlFileName,
                    url = model.ggmlUrl,
                    expectedSha256 = model.ggmlSha256,
                    expectedSizeBytes = model.ggmlSizeBytes
                )
            )
            ModelEngine.SHERPA_ONNX -> listOf(
                DownloadItem(
                    filename = "${model.id}-tokens.txt",
                    url = model.tokensUrl,
                    expectedSha256 = model.tokensSha256,
                    expectedSizeBytes = model.tokensSizeBytes
                ),
                DownloadItem(
                    filename = "${model.id}-encoder.int8.onnx",
                    url = model.encoderUrl,
                    expectedSha256 = model.encoderSha256,
                    expectedSizeBytes = model.encoderSizeBytes
                ),
                DownloadItem(
                    filename = "${model.id}-decoder.int8.onnx",
                    url = model.decoderUrl,
                    expectedSha256 = model.decoderSha256,
                    expectedSizeBytes = model.decoderSizeBytes
                )
            )
        }

        try {
            val sumExactSizes = filesToDownload.sumOf { it.expectedSizeBytes }
            val totalSizeApprox = if (sumExactSizes > 0L) sumExactSizes else model.sizeMb * 1024L * 1024L
            downloadAndVerifyItems(filesToDownload, targetDir, totalSizeApprox, onProgress)
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
        val mediaDir = File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI/models/speaker")
        val fallbackDir = File("/storage/emulated/0/GalaxyAI/models/speaker")
        val targetDir = resolveWritableModelDir(mediaDir, fallbackDir)

        val filesToDownload = listOf(
            DownloadItem(
                filename = "segmentation.onnx",
                url = diarizationInfo.segmentationUrl,
                expectedSha256 = diarizationInfo.segmentationSha256,
                expectedSizeBytes = diarizationInfo.segmentationSizeBytes
            ),
            DownloadItem(
                filename = "embedding.onnx",
                url = diarizationInfo.embeddingUrl,
                expectedSha256 = diarizationInfo.embeddingSha256,
                expectedSizeBytes = diarizationInfo.embeddingSizeBytes
            )
        )

        try {
            val sumExactSizes = filesToDownload.sumOf { it.expectedSizeBytes }
            val totalSizeApprox = if (sumExactSizes > 0L) sumExactSizes else diarizationInfo.sizeMb * 1024L * 1024L
            downloadAndVerifyItems(filesToDownload, targetDir, totalSizeApprox, onProgress)
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
    ): String {
        var currentUrl = urlStr
        var redirects = 0
        var conn: HttpURLConnection? = null

        while (redirects < 10) {
            val url = URL(currentUrl)
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "GalaxyAiOfflineStt/1.0")
            conn.connectTimeout = 20000
            conn.readTimeout = 40000
            val code = conn.responseCode
            if (code in 301..308) {
                val location = conn.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirect ($code) without Location from $currentUrl")
                val resolvedUrl = URL(url, location)
                if (url.protocol.equals("https", ignoreCase = true) &&
                    resolvedUrl.protocol.equals("http", ignoreCase = true)
                ) {
                    conn.disconnect()
                    throw IllegalStateException("Refused HTTPS -> HTTP redirect to $resolvedUrl")
                }
                currentUrl = resolvedUrl.toString()
                redirects++
                conn.disconnect()
            } else {
                break
            }
        }

        if (conn == null || conn.responseCode != 200) {
            val code = conn?.responseCode
            conn?.disconnect()
            throw IllegalStateException("HTTP $code error while downloading from $currentUrl")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        try {
            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        onBytesRead(bytesRead)
                    }
                    output.flush()
                }
            }
        } finally {
            conn.disconnect()
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
