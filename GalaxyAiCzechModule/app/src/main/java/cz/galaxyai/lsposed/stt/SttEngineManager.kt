package cz.galaxyai.lsposed.stt

import android.content.Context
import android.net.Uri
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import cz.galaxyai.lsposed.MainHook.Companion.TAG
import cz.galaxyai.lsposed.config.ModuleConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

data class WordSegment(
    val word: String,
    val startMs: Int,
    val endMs: Int
)

data class SegmentInfo(
    val startMs: Int,
    val endMs: Int,
    val speakerId: Int = 0
)

data class SttResult(
    val text: String,
    val sentences: List<String>,
    val segments: List<SegmentInfo>,
    val words: List<WordSegment>,
    val timingInfo: IntArray
)

data class AudioSegment(
    val startSample: Int,
    val endSample: Int,
    val startMs: Int,
    val endMs: Int
)

/** Slovo s časováním odvozeným z token timestampů Whisperu (přesnější než rovnoměrné dělení). */
private data class TimedWord(
    val text: String,
    val startMs: Int,
    val endMs: Int
)

object SttEngineManager {

    private const val SPEAKER_SEGMENTATION_FILE = "segmentation.onnx"

    /** Kolik různých mluvčích se předá do Diktafonu (UI kreslí 4 barvy). */
    private const val MAX_SPEAKERS = 4
    private const val SPEAKER_EMBEDDING_FILE = "embedding.onnx"

    @Volatile
    private var recognizer: OfflineRecognizer? = null
    @Volatile
    private var activeModelId: String? = null
    @Volatile
    private var activeLanguageCode: String? = null

    @Volatile
    var isNativeLoaded = false

    fun ensureNativeLibraries() {
        if (isNativeLoaded) return
        synchronized(this) {
            if (isNativeLoaded) return

            val libs = listOf(
                "libonnxruntime.so",
                "libsherpa-onnx-c-api.so",
                "libsherpa-onnx-cxx-api.so",
                "libsherpa-onnx-jni.so"
            )

            val app = try {
                val atClass = Class.forName("android.app.ActivityThread")
                atClass.getMethod("currentApplication").invoke(null) as? android.app.Application
            } catch (_: Throwable) { null }

            val cacheDir = app?.cacheDir ?: File("/data/user/0/com.samsung.android.intellivoiceservice/cache")
            val targetLibDir = File(cacheDir, "galaxyai_native_libs").apply { mkdirs() }

            // Zjištění cesty k base.apk našeho modulu (i při platnosti Android 11+ Package Visibility)
            var apkFile: File? = null

            // Metoda 1: Získání z MainHook.modulePath (přímo z Xposed initZygote)
            if (cz.galaxyai.lsposed.MainHook.modulePath != null) {
                val f = File(cz.galaxyai.lsposed.MainHook.modulePath!!)
                if (f.exists()) apkFile = f
            }

            // Metoda 2: Získání z LspModuleClassLoader řetězce
            if (apkFile == null) {
                val clStr = SttEngineManager::class.java.classLoader?.toString() ?: ""
                val moduleMatch = Regex("module=([^,\\]]+)").find(clStr)
                if (moduleMatch != null) {
                    val f = File(moduleMatch.groupValues[1].trim())
                    if (f.exists()) apkFile = f
                }
            }

            // Metoda 3: Prohledání /data/app
            if (apkFile == null) {
                try {
                    val appDirs = File("/data/app").listFiles()
                    if (appDirs != null) {
                        for (dir in appDirs) {
                            if (dir.name.contains("cz.galaxyai.lsposed")) {
                                val base = File(dir, "base.apk")
                                if (base.exists()) {
                                    apkFile = base
                                    break
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }

            // Metoda 4: Přes PackageManager
            if (apkFile == null) {
                try {
                    val pm = app?.packageManager
                    val moduleInfo = pm?.getApplicationInfo("cz.galaxyai.lsposed", 0)
                    val sDir = moduleInfo?.sourceDir
                    if (sDir != null && File(sDir).exists()) {
                        apkFile = File(sDir)
                    }
                } catch (_: Throwable) {}
            }

            Log.i(TAG, "[SttEngine] Nalezen base.apk modulu: ${apkFile?.absolutePath}")

            // 2. Načtení z rozbalené složky lib/arm64 vedle base.apk
            if (apkFile != null) {
                val libDir = File(apkFile.parentFile, "lib/arm64")

                // Vložení adresářů s nativními knihovnami do DexPathList třídního zavaděče
                try {
                    val cl = SttEngineManager::class.java.classLoader
                    val pathList = de.robv.android.xposed.XposedHelpers.getObjectField(cl, "pathList")
                    @Suppress("UNCHECKED_CAST")
                    val nativeDirs = de.robv.android.xposed.XposedHelpers.getObjectField(pathList, "nativeLibraryDirectories") as? ArrayList<File>
                    if (nativeDirs != null) {
                        if (libDir.exists() && !nativeDirs.contains(libDir)) nativeDirs.add(0, libDir)
                        if (!nativeDirs.contains(targetLibDir)) nativeDirs.add(0, targetLibDir)
                    }
                } catch (_: Throwable) {}

                if (libDir.exists() && File(libDir, "libonnxruntime.so").exists()) {
                    try {
                        for (libName in libs) {
                            val f = File(libDir, libName)
                            System.load(f.absolutePath)
                            Log.i(TAG, "[SttEngine] Načtena knihovna z lib/arm64: ${f.absolutePath}")
                        }
                        isNativeLoaded = true
                        Log.i(TAG, "[SttEngine] Všechny nativní knihovny úspěšně načteny z lib/arm64!")
                        return
                    } catch (t: Throwable) {
                        Log.w(TAG, "[SttEngine] Načtení z lib/arm64 selhalo: ${t.message}")
                    }
                }

                // 3. Extrakce přímo z base.apk do cacheDir
                try {
                    val zip = ZipFile(apkFile)
                    for (libName in libs) {
                        val entry = zip.getEntry("lib/arm64-v8a/$libName")
                        if (entry != null) {
                            val dstFile = File(targetLibDir, libName)
                            if (!dstFile.exists() || dstFile.length() != entry.size) {
                                zip.getInputStream(entry).use { input ->
                                    dstFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        }
                    }
                    zip.close()

                    for (libName in libs) {
                        val f = File(targetLibDir, libName)
                        System.load(f.absolutePath)
                        Log.i(TAG, "[SttEngine] Úspěšně načtena knihovna extrahovaná z APK do cacheDir: ${f.absolutePath}")
                    }
                    isNativeLoaded = true
                    Log.i(TAG, "[SttEngine] Všechny nativní knihovny úspěšně extrahovány a načteny!")
                    return
                } catch (t: Throwable) {
                    Log.e(TAG, "[SttEngine] Extrakce z base.apk do cacheDir selhala: ${t.message}", t)
                }
            }

            // 4. Zkopírování .so souborů z /storage/emulated/0 do spustitelného cacheDir hostitelské aplikace
            // (Na /storage/emulated/0 platí noexec příznak jádra, v cacheDir je spouštění nativních knihoven povoleno)
            val storageLibDirs = listOf(
                File("/storage/emulated/0/GalaxyAI/models/lib/arm64-v8a"),
                File("/sdcard/GalaxyAI/models/lib/arm64-v8a")
            )

            for (srcDir in storageLibDirs) {
                if (srcDir.exists() && srcDir.isDirectory) {
                    try {
                        var allCopied = true
                        for (libName in libs) {
                            val srcFile = File(srcDir, libName)
                            val dstFile = File(targetLibDir, libName)
                            if (srcFile.exists()) {
                                if (!dstFile.exists() || dstFile.length() != srcFile.length()) {
                                    srcFile.copyTo(dstFile, overwrite = true)
                                }
                            } else {
                                allCopied = false
                            }
                        }

                        if (allCopied) {
                            for (libName in libs) {
                                val f = File(targetLibDir, libName)
                                System.load(f.absolutePath)
                                Log.i(TAG, "[SttEngine] Úspěšně načtena knihovna zkopírovaná do cacheDir: ${f.absolutePath}")
                            }
                            isNativeLoaded = true
                            Log.i(TAG, "[SttEngine] Všechny nativní knihovny úspěšně načteny přes cacheDir!")
                            return
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "[SttEngine] Chyba při kopírování/načítání knihoven ze storage: ${e.message}", e)
                    }
                }
            }
        }
    }

    /**
     * Počet vláken pro ONNX Runtime. Zařízení s 8 jádry zvládne 6 vláken bez zahřívání;
     * u menších zařízení necháváme rezervu pro UI a systém.
     */
    private fun decoderThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        return when {
            cores >= 8 -> 6
            cores >= 6 -> 4
            else -> (cores - 1).coerceAtLeast(2)
        }
    }

    private fun getOrResolveModelFile(context: Context?, modelId: String, fileName: String): File? {
        val mediaDirFile = File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/whisper-$modelId", fileName)
        if (mediaDirFile.exists() && mediaDirFile.length() > 0) {
            try {
                mediaDirFile.inputStream().use {
                    if (it.read() != -1) {
                        Log.i(TAG, "[SttEngine] Nalezen a ověřen model v media dir: ${mediaDirFile.absolutePath} (${mediaDirFile.length()} B)")
                        return mediaDirFile
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[SttEngine] Nelze číst přímo z media dir: ${t.message}")
            }
        }

        val storageFile = File("/storage/emulated/0/GalaxyAI/models/whisper-$modelId", fileName)
        val altFile = File("/sdcard/GalaxyAI/models/whisper-$modelId", fileName)
        val candidate = if (storageFile.exists()) storageFile else if (altFile.exists()) altFile else null

        // Pokud máme context aplikace hostitele, zkontrolujeme lokální soukromé úložiště
        if (context != null) {
            val localDir = File(context.filesDir, "models/whisper-$modelId").apply { mkdirs() }
            val localFile = File(localDir, fileName)

            // Pokud lokální soubor již existuje a je kompletní, použijeme jej přímo
            if (localFile.exists() && localFile.length() > 0) {
                val cLen = try { candidate?.length() ?: 0L } catch (_: Throwable) { 0L }
                if (cLen == 0L || localFile.length() == cLen) {
                    return localFile
                }
            }

            // Otestujeme, zda lze ze storage číst přímo
            if (candidate != null && candidate.exists()) {
                var canReadDirectly = false
                try {
                    candidate.inputStream().use {
                        val b = it.read()
                        canReadDirectly = (b != -1)
                    }
                } catch (_: Throwable) {
                    canReadDirectly = false
                }
                if (canReadDirectly) {
                    return candidate
                }
            }

            // Pokud máme mediaDirFile, zkusíme jej zkopírovat do lokálního filesDir
            if (mediaDirFile.exists() && mediaDirFile.length() > 0) {
                try {
                    mediaDirFile.copyTo(localFile, overwrite = true)
                    if (localFile.exists() && localFile.length() > 0) {
                        Log.i(TAG, "[SttEngine] $fileName úspěšně zkopírován z media dir do ${localFile.absolutePath}")
                        return localFile
                    }
                } catch (_: Throwable) {}
            }

            // Zkusíme zkopírovat soubor přes ModelProvider našeho modulu do soukromého úložiště intellivoiceservice
            try {
                val uri = Uri.parse("content://cz.galaxyai.lsposed.modelprovider/whisper-$modelId/$fileName")
                Log.i(TAG, "[SttEngine] Kopíruji $fileName přes ModelProvider do ${localFile.absolutePath}...")
                val tmpFile = File(localDir, "$fileName.tmp")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmpFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (tmpFile.exists() && tmpFile.length() > 0) {
                    tmpFile.renameTo(localFile)
                    Log.i(TAG, "[SttEngine] $fileName úspěšně zkopírován do lokálního úložiště (${localFile.length()} B).")
                    return localFile
                }
            } catch (t: Throwable) {
                Log.e(TAG, "[SttEngine] Chyba při kopírování $fileName přes ContentProvider: ${t.message}", t)
            }
        }

        return candidate
    }

    private fun getOrInitRecognizer(targetWhisperLang: String): OfflineRecognizer? {
        val config = ModuleConfig.load()
        val preferredModel = config.selectedModel

        if (recognizer != null && activeModelId == preferredModel && activeLanguageCode == targetWhisperLang) {
            return recognizer
        }

        synchronized(this) {
            if (recognizer != null && activeModelId == preferredModel && activeLanguageCode == targetWhisperLang) {
                return recognizer
            }

            try {
                ensureNativeLibraries()

                var modelId = preferredModel
                var modelDir = ModuleConfig.getModelDirectory(modelId)

                if (modelDir == null) {
                    for (fallback in listOf("turbo", "small", "base", "tiny")) {
                        val d = ModuleConfig.getModelDirectory(fallback)
                        if (d != null) {
                            modelId = fallback
                            modelDir = d
                            break
                        }
                    }
                }

                val app = try {
                    val atClass = Class.forName("android.app.ActivityThread")
                    atClass.getMethod("currentApplication").invoke(null) as? android.app.Application
                } catch (_: Throwable) { null }

                val encoderFile = getOrResolveModelFile(app, modelId, "$modelId-encoder.int8.onnx")
                val decoderFile = getOrResolveModelFile(app, modelId, "$modelId-decoder.int8.onnx")
                val tokensFile = getOrResolveModelFile(app, modelId, "$modelId-tokens.txt")

                if (encoderFile == null || !encoderFile.exists() ||
                    decoderFile == null || !decoderFile.exists() ||
                    tokensFile == null || !tokensFile.exists()) {
                    Log.w(TAG, "[SttEngine] Modelové soubory pro Whisper '$modelId' nebyly nalezeny (enc=$encoderFile, dec=$decoderFile, tok=$tokensFile).")
                    return null
                }

                val whisperConfig = OfflineWhisperModelConfig().apply {
                    encoder = encoderFile.absolutePath
                    decoder = decoderFile.absolutePath
                    language = targetWhisperLang
                    task = "transcribe"
                    tailPaddings = 1000
                    enableTokenTimestamps = true
                    enableSegmentTimestamps = true
                }

                val modelConfig = OfflineModelConfig().apply {
                    whisper = whisperConfig
                    tokens = tokensFile.absolutePath
                    numThreads = decoderThreads()
                    provider = "cpu"
                    modelType = "whisper"
                }

                val featConfig = FeatureConfig().apply {
                    sampleRate = 16000
                    featureDim = 80
                }

                val recognizerConfig = OfflineRecognizerConfig().apply {
                    this.featConfig = featConfig
                    this.modelConfig = modelConfig
                }

                Log.i(TAG, "[SttEngine] Inicializuji Whisper '$modelId' pro jazyk '$targetWhisperLang'...")
                try {
                    recognizer?.release()
                } catch (_: Throwable) {}

                val instance = OfflineRecognizer(null, recognizerConfig)
                recognizer = instance
                activeModelId = modelId
                activeLanguageCode = targetWhisperLang

                Log.i(TAG, "[SttEngine] OfflineRecognizer úspěšně inicializován ($modelId, $targetWhisperLang)!")
                return instance
            } catch (t: Throwable) {
                Log.e(TAG, "[SttEngine] Selhala inicializace OfflineRecognizer: ${t.message}", t)
                return null
            }
        }
    }

    /**
     * VAD detekce řečových segmentů založená na energii signálu.
     * Zabraňuje ořezání audia delšího než 30 sekund a zajišťuje přesné časování slov a bublin.
     */
    private fun detectSpeechSegments(floatSamples: FloatArray, sampleRate: Int = 16000): List<AudioSegment> {
        val totalSamples = floatSamples.size
        val totalMs = (totalSamples * 1000L / sampleRate).toInt()
        if (totalSamples == 0) return emptyList()

        // 20 ms frame = 320 vzorků při 16 kHz
        val frameSize = sampleRate / 50
        val numFrames = totalSamples / frameSize
        if (numFrames == 0) {
            return listOf(AudioSegment(0, totalSamples, 0, totalMs))
        }

        val energies = FloatArray(numFrames)
        var maxEnergy = 0f
        for (f in 0 until numFrames) {
            val start = f * frameSize
            var sumSq = 0.0
            for (i in 0 until frameSize) {
                val s = floatSamples[start + i]
                sumSq += (s * s)
            }
            val rms = Math.sqrt(sumSq / frameSize).toFloat()
            energies[f] = rms
            if (rms > maxEnergy) maxEnergy = rms
        }

        val speechThreshold = (maxEnergy * 0.12f).coerceIn(0.008f, 0.035f)
        val isSpeech = BooleanArray(numFrames) { energies[it] >= speechThreshold }

        // Segmentace bublin a mluvčích: max délka bubliny 9 sekund (450 rámců),
        // min pauza pro rozdělení řečníků/vět 260 ms (13 rámců), min délka řeči 240 ms (12 rámců)
        val maxSegFrames = 450
        val minPauseFrames = 13
        val minSpeechFrames = 12

        val segments = mutableListOf<AudioSegment>()
        var segStartFrame = -1
        var lastSpeechFrame = -1
        var f = 0

        while (f < numFrames) {
            if (isSpeech[f]) {
                if (segStartFrame == -1) {
                    segStartFrame = f
                }
                lastSpeechFrame = f
            } else {
                if (segStartFrame != -1) {
                    val silenceDuration = f - lastSpeechFrame
                    val currentSegLength = f - segStartFrame
                    if (silenceDuration >= minPauseFrames || currentSegLength >= maxSegFrames) {
                        val speechLen = lastSpeechFrame - segStartFrame + 1
                        if (speechLen >= minSpeechFrames) {
                            // 160 ms padding před a za
                            val paddedStart = (segStartFrame - 8).coerceAtLeast(0)
                            val paddedEnd = (lastSpeechFrame + 8).coerceAtMost(numFrames - 1)
                            val startSample = paddedStart * frameSize
                            val endSample = Math.min((paddedEnd + 1) * frameSize, totalSamples)
                            val startMs = (startSample * 1000L / sampleRate).toInt()
                            val endMs = (endSample * 1000L / sampleRate).toInt()
                            segments.add(AudioSegment(startSample, endSample, startMs, endMs))
                        }
                        segStartFrame = -1
                        lastSpeechFrame = -1
                    }
                }
            }
            f++
        }

        if (segStartFrame != -1) {
            val speechLen = (lastSpeechFrame - segStartFrame + 1).coerceAtLeast(1)
            if (speechLen >= minSpeechFrames || segments.isEmpty()) {
                val paddedStart = (segStartFrame - 8).coerceAtLeast(0)
                val startSample = paddedStart * frameSize
                val endSample = totalSamples
                val startMs = (startSample * 1000L / sampleRate).toInt()
                val endMs = totalMs
                segments.add(AudioSegment(startSample, endSample, startMs, endMs))
            }
        }

        // Záložní dělení po 24 sekundách, pokud VAD nic nezachytil
        if (segments.isEmpty()) {
            val chunkSamples = 24 * sampleRate
            var curr = 0
            while (curr < totalSamples) {
                val end = Math.min(curr + chunkSamples, totalSamples)
                val sMs = (curr * 1000L / sampleRate).toInt()
                val eMs = (end * 1000L / sampleRate).toInt()
                segments.add(AudioSegment(curr, end, sMs, eMs))
                curr = end
            }
        }

        return segments
    }

    /**
     * Sloučí krátké řečové ostrůvky do delších dekódovacích oken.
     *
     * Whisper potřebuje kontext — dekódování po 260ms pauzách produkovalo nesmysly
     * typu "Abyd vě" / "Kusil byta", protože každý úlomek byl přepsán izolovaně.
     * Okna proto spojují sousední ostrůvky stejného mluvčího, pokud je mezi nimi
     * mezera menší než [joinGapMs], a omezí se na [maxWindowMs].
     *
     * Měřeno na SM-S918B: každé volání dekodéru má pevnou režii ~2,5 s bez ohledu
     * na délku okna (okno 0,6 s trvá 2,6 s, okno 24,7 s trvá 10,3 s). Delší okna
     * jsou proto výrazně efektivnější i kvalitnější — 30 s je přirozená délka
     * kontextu Whisperu.
     */
    /**
     * Pro monology (hlasové schránky, diktáty) rozdělí audio na plynulá dlouhá okna (až 28 sekund)
     * s jednotným mluvčím (speakerId = 0 -> Mluvčí 1).
     * Zabraňuje fragmentaci na sub-sekundové úseky a halucinacím Whisperu.
     */
    private fun buildMonologueWindows(
        totalDurationMs: Int,
        floatSamples: FloatArray,
        sampleRate: Int = 16000,
        maxWindowMs: Int = 28000
    ): List<SegmentInfo> {
        if (totalDurationMs <= maxWindowMs) {
            return listOf(SegmentInfo(0, totalDurationMs, 0))
        }

        val windows = mutableListOf<SegmentInfo>()
        var startMs = 0

        while (startMs < totalDurationMs) {
            val nominalEnd = Math.min(startMs + maxWindowMs, totalDurationMs)
            if (nominalEnd >= totalDurationMs) {
                windows.add(SegmentInfo(startMs, totalDurationMs, 0))
                break
            }

            // Hledáme bod s nejnižší energií (pauzu/ticho) v intervalu [nominalEnd - 3000 ms .. nominalEnd]
            val searchStartMs = Math.max(startMs + 10000, nominalEnd - 3000)
            var bestCutMs = nominalEnd
            var minRms = Float.MAX_VALUE

            val stepMs = 100
            var t = searchStartMs
            while (t < nominalEnd) {
                val sampleIdx = ((t.toLong() * sampleRate) / 1000L).toInt().coerceIn(0, floatSamples.size - 1)
                val windowSize = (stepMs * sampleRate / 1000).coerceAtMost(floatSamples.size - sampleIdx)
                var sumSq = 0f
                for (s in 0 until windowSize) {
                    val v = floatSamples[sampleIdx + s]
                    sumSq += v * v
                }
                val rms = Math.sqrt((sumSq / windowSize.coerceAtLeast(1)).toDouble()).toFloat()
                if (rms < minRms) {
                    minRms = rms
                    bestCutMs = t
                }
                t += stepMs
            }

            windows.add(SegmentInfo(startMs, bestCutMs, 0))
            startMs = bestCutMs
        }

        return windows.filter { it.endMs > it.startMs }
    }

    private fun buildDecodeWindows(
        diarized: List<SegmentInfo>,
        floatSamples: FloatArray,
        sampleRate: Int = 16000,
        joinGapMs: Int = 1800,
        maxWindowMs: Int = 30000,
        minWindowMs: Int = 2500
    ): List<SegmentInfo> {
        if (diarized.isEmpty()) return emptyList()

        val totalSamples = floatSamples.size
        val totalMs = (totalSamples * 1000L / sampleRate).toInt()
        val windows = mutableListOf<SegmentInfo>()
        var winStart = diarized[0].startMs
        var winEnd = diarized[0].endMs
        var winSpeaker = diarized[0].speakerId

        for (i in 1 until diarized.size) {
            val seg = diarized[i]
            val gap = seg.startMs - winEnd
            val wouldBeLength = seg.endMs - winStart
            val canMerge = gap <= joinGapMs && seg.speakerId == winSpeaker && wouldBeLength <= maxWindowMs
            if (canMerge) {
                winEnd = seg.endMs
            } else {
                windows.add(SegmentInfo(winStart, winEnd, winSpeaker))
                winStart = seg.startMs
                winEnd = seg.endMs
                winSpeaker = seg.speakerId
            }
        }
        windows.add(SegmentInfo(winStart, winEnd, winSpeaker))

        // Ošetření minimální délky okna:
        // Whisper potřebuje alespoň 2.5 s kontextu, aby nehalucinoval na sub-sekundových úsecích.
        val expanded = mutableListOf<SegmentInfo>()
        for (w in windows) {
            val dur = w.endMs - w.startMs
            if (dur >= minWindowMs) {
                expanded.add(w)
            } else {
                val needed = minWindowMs - dur
                val padBefore = needed / 2
                val padAfter = needed - padBefore
                val newStart = (w.startMs - padBefore).coerceAtLeast(0)
                val newEnd = (w.endMs + padAfter).coerceAtMost(totalMs)
                expanded.add(SegmentInfo(newStart, newEnd, w.speakerId))
            }
        }

        // Rozdělení příliš dlouhých oken na části, aby dekódování zůstalo rychlé a stabilní
        val result = mutableListOf<SegmentInfo>()
        for (w in expanded) {
            val length = w.endMs - w.startMs
            if (length <= maxWindowMs) {
                result.add(w)
                continue
            }
            var start = w.startMs
            while (start < w.endMs) {
                val end = Math.min(start + maxWindowMs, w.endMs)
                result.add(SegmentInfo(start, end, w.speakerId))
                start = end
            }
        }

        // Oříznutí na rozsah audia
        return result.filter { it.endMs > it.startMs }
            .map { SegmentInfo(it.startMs.coerceIn(0, totalMs), it.endMs.coerceIn(0, totalMs), it.speakerId) }
            .filter { it.endMs > it.startMs }
    }

    /**
     * Převede token timestampy Whisperu na slova s reálným časováním.
     * Whisper BPE tokeny začínají mezerou (nebo "Ġ") u nového slova, interpunkce jde bez mezery.
     * Když model timestampy nevrátí, časování se rozpočítá proporcionálně podle délky slov.
     */
    private fun extractTimedWords(
        res: com.k2fsa.sherpa.onnx.OfflineRecognizerResult,
        windowStartMs: Int,
        windowEndMs: Int
    ): List<TimedWord> {
        val text = res.text.trim()
        val splitWords = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (splitWords.isEmpty()) return emptyList()

        val rawTokens = res.tokens
        val rawTs = res.timestamps

        if (rawTokens == null || rawTs == null || rawTokens.isEmpty() || rawTs.isEmpty()) {
            return distributeWords(text, windowStartMs, windowEndMs)
        }

        val result = mutableListOf<TimedWord>()
        var tokenIdx = 0
        var currentCursorMs = windowStartMs

        for (word in splitWords) {
            val cleanWord = word.trim()
            var wordStartMs = -1
            var wordEndMs = -1
            val consumed = StringBuilder()

            while (tokenIdx < rawTokens.size && consumed.length < cleanWord.length) {
                val token = rawTokens[tokenIdx]
                val ts = if (tokenIdx < rawTs.size) rawTs[tokenIdx] else 0f
                tokenIdx++

                if (token == null || token.isEmpty()) continue
                if (token.startsWith("<|") && token.endsWith("|>")) continue

                val cleanToken = token.replace("Ġ", " ").trim()
                if (cleanToken.isEmpty()) continue

                val tStart = windowStartMs + (ts * 1000f).toInt().coerceIn(0, windowEndMs - windowStartMs)
                if (wordStartMs == -1) {
                    wordStartMs = tStart
                }
                wordEndMs = tStart + 160
                consumed.append(cleanToken.replace(" ", ""))
            }

            if (wordStartMs == -1) {
                wordStartMs = currentCursorMs
            }
            wordStartMs = Math.max(wordStartMs, currentCursorMs)
            if (wordEndMs <= wordStartMs) {
                wordEndMs = wordStartMs + 120
            }
            wordEndMs = Math.min(wordEndMs, windowEndMs)

            result.add(TimedWord(cleanWord, wordStartMs, wordEndMs))
            currentCursorMs = wordStartMs + 50
        }

        return result
    }

    /** Záložní rovnoměrné rozpočítání časování podle počtu znaků. */
    private fun distributeWords(text: String, windowStartMs: Int, windowEndMs: Int): List<TimedWord> {
        val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()
        val totalChars = parts.sumOf { it.length }.coerceAtLeast(1)
        val span = (windowEndMs - windowStartMs).coerceAtLeast(parts.size * 80)
        var cursor = windowStartMs
        val out = mutableListOf<TimedWord>()
        for (p in parts) {
            val len = Math.max(1, p.length)
            val duration = (span * len) / totalChars
            val end = Math.min(cursor + Math.max(80, duration), windowEndMs)
            out.add(TimedWord(p, cursor, Math.max(cursor + 60, end)))
            cursor = Math.min(end + 20, windowEndMs)
        }
        return out
    }

    /** Rozdělí slova na věty (bubliny) podle koncové interpunkce a delších mezer. */
    private fun splitIntoSentences(words: List<TimedWord>, gapMs: Int = 700): List<List<TimedWord>> {
        if (words.isEmpty()) return emptyList()
        val sentences = mutableListOf<List<TimedWord>>()
        var current = mutableListOf<TimedWord>()

        for (i in words.indices) {
            val w = words[i]
            current.add(w)

            val endsSentence = w.text.lastOrNull()?.let { it == '.' || it == '!' || it == '?' || it == '…' } == true
            val nextGap = if (i + 1 < words.size) words[i + 1].startMs - w.endMs else Int.MAX_VALUE
            if (endsSentence || nextGap >= gapMs) {
                sentences.add(current)
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) sentences.add(current)
        return sentences
    }

    /**
     * Akustická analýza řečových úseků k rozlišení mluvčích (např. volající vs. uživatel při hovoru).
     * Počítá poměr vysokých frekvencí (>3.5 kHz vs <3.5 kHz) a zero-crossing rate.
     * V nahrávkách hovorů je vzdálený volající telefonně filtrován (<3.4 kHz),
     * zatímco lokální mikrofon zachycuje celé pásmo (až 8 kHz).
     */
    fun performAcousticDiarization(
        floatSamples: FloatArray,
        segments: List<AudioSegment>,
        sampleRate: Int = 16000
    ): List<SegmentInfo> {
        if (segments.isEmpty()) return emptyList()
        if (segments.size == 1) {
            return listOf(SegmentInfo(segments[0].startMs, segments[0].endMs, 0))
        }

        val scores = FloatArray(segments.size)
        for (i in segments.indices) {
            val seg = segments[i]
            val len = seg.endSample - seg.startSample
            if (len <= 0) continue

            var zeroCrossings = 0
            var highFreqEnergy = 0.0
            var totalEnergy = 0.0

            for (j in 1 until len) {
                val s0 = floatSamples[seg.startSample + j - 1]
                val s1 = floatSamples[seg.startSample + j]
                totalEnergy += (s1 * s1)
                val diff = s1 - s0
                highFreqEnergy += (diff * diff)

                if ((s0 >= 0f && s1 < 0f) || (s0 < 0f && s1 >= 0f)) {
                    zeroCrossings++
                }
            }

            val zcr = zeroCrossings.toFloat() / len.coerceAtLeast(1)
            val hfRatio = (highFreqEnergy / totalEnergy.coerceAtLeast(1e-6)).toFloat()
            scores[i] = hfRatio * 0.7f + zcr * 0.3f
        }

        var minScore = Float.MAX_VALUE
        var maxScore = Float.MIN_VALUE
        for (s in scores) {
            if (s < minScore) minScore = s
            if (s > maxScore) maxScore = s
        }

        val speakerAssignments = IntArray(segments.size)

        // Samsung VoiceNote displays (SpeechInfo.speaker + 1) in UI (0 -> "Mluvčí 1", 1 -> "Mluvčí 2")
        // Práh 0.04 se ukázal jako příliš přísný – u hovorových nahrávek se rozdíl skóre
        // často drží pod ním a všechno skončí jako jeden mluvčí. Rozhodujeme proto podle
        // relativního rozpětí a požadujeme jen malý absolutní odstup mezi shluky.
        val scoreRange = maxScore - minScore
        val scoreMean = scores.average()
        Log.i(TAG, "[SttEngine] Diarizace: ${segments.size} ostrůvků, skóre min=${String.format(Locale.US, "%.4f", minScore)} max=${String.format(Locale.US, "%.4f", maxScore)} průměr=${String.format(Locale.US, "%.4f", scoreMean)} rozpětí=${String.format(Locale.US, "%.4f", scoreRange)}")

        // Rozdělení na dva mluvčí jen když je rozdíl dostatečně velký a oba shluky
        // jsou rozumně zastoupené. Dřívější práh 0,04 nerozdělil nic, snížený práh 0,012
        // naopak označil 2 z 34 oken jako druhého mluvčího – to je šum, ne diarizace.
        // NOTES.md: prioritou je stabilní přepis, fallback je jeden mluvčí.
        val enoughSeparation = scoreRange > 0.02f
        if (enoughSeparation) {
            var mean1 = minScore
            var mean2 = maxScore

            for (iter in 0 until 6) {
                var sum1 = 0.0
                var count1 = 0
                var sum2 = 0.0
                var count2 = 0

                for (i in scores.indices) {
                    val d1 = Math.abs(scores[i] - mean1)
                    val d2 = Math.abs(scores[i] - mean2)
                    if (d1 <= d2) {
                        speakerAssignments[i] = 0 // Speaker 1
                        sum1 += scores[i]
                        count1++
                    } else {
                        speakerAssignments[i] = 1 // Speaker 2
                        sum2 += scores[i]
                        count2++
                    }
                }

                if (count1 > 0) mean1 = (sum1 / count1).toFloat()
                if (count2 > 0) mean2 = (sum2 / count2).toFloat()
            }

            // Oba shluky musí mít smysluplný podíl, jinak jde o odlehlé hodnoty
            val minor = speakerAssignments.count { it == 1 }.coerceAtMost(speakerAssignments.size - speakerAssignments.count { it == 1 })
            val minorRatio = minor.toFloat() / speakerAssignments.size.coerceAtLeast(1)
            Log.i(TAG, "[SttEngine] Diarizace: menšinový shluk ${minor}/${speakerAssignments.size} (${String.format(Locale.US, "%.0f", minorRatio * 100)} %)")
            if (minorRatio < 0.15f) {
                Log.i(TAG, "[SttEngine] Diarizace: menšinový shluk je příliš malý, používám jednoho mluvčího.")
                speakerAssignments.fill(0)
            }
        } else {
            // Bez dostatečného akustického rozdílu ponecháme jednoho mluvčího (Mluvčí 1)
            // pro stabilní a přirozený přepis bez falešného střídání mluvčích na pauzách
            speakerAssignments.fill(0)
        }

        return segments.mapIndexed { idx, seg ->
            SegmentInfo(seg.startMs, seg.endMs, speakerAssignments[idx])
        }
    }

    /**
     * Segmenty z vlastního VAD + akustické diarizace, sloučené do dekódovacích oken.
     * Nejdřív zkusí skutečný diarizér (sherpa-onnx: pyannote segmentace + speaker embedding),
     * a teprve když jeho modely nejsou k dispozici, spadne na levnou akustickou heuristiku.
     */
    private fun internalSegments(floatSamples: FloatArray): List<SegmentInfo> {
        val diarized = sherpaDiarize(floatSamples)
        if (diarized != null) {
            val speakers = diarized.map { it.speakerId }.distinct().size
            Log.i(TAG, "[SttEngine] Diarizace mluvčích: ${diarized.size} úseků, $speakers mluvčích (sherpa-onnx).")
            val windows = buildDecodeWindows(diarized, floatSamples, 16000)
            Log.i(TAG, "[SttEngine] Sloučeno do ${windows.size} dekódovacích oken (průměr ${windows.sumOf { it.endMs - it.startMs } / windows.size.coerceAtLeast(1)} ms).")
            return windows
        }

        // 1) Jemné řečové ostrůvky (jen pro diarizaci) – krátké pauzy 260 ms stačí.
        val islands = detectSpeechSegments(floatSamples, 16000)
        Log.i(TAG, "[SttEngine] Detekováno ${islands.size} řečových ostrůvků pomocí VAD.")
        val heuristic = performAcousticDiarization(floatSamples, islands, 16000)

        // 2) Sloučení do delších dekódovacích oken, aby Whisper dostal kontext.
        //    Příliš krátké segmenty (dřív max. 9 s) ořezávaly slova a ničily kvalitu přepisu.
        val windows = buildDecodeWindows(heuristic, floatSamples, 16000)
        Log.i(TAG, "[SttEngine] Sloučeno do ${windows.size} dekódovacích oken (průměr ${windows.sumOf { it.endMs - it.startMs } / windows.size.coerceAtLeast(1)} ms).")
        return windows
    }

    /**
     * Skutečné rozdělení mluvčích přes sherpa-onnx (pyannote segmentation 3.0 + 3D-Speaker
     * campplus embedding + fast clustering).
     *
     * Samsung ManagedSpeakerDiarizer tady použít nelze: v této verzi IntelliVoiceServices
     * chybí nativní knihovna, volání končí na
     * `UnsatisfiedLinkError: No implementation found for void
     * com.samsung.android.ai.sd.SpeakerDiarization.native_setup(java.lang.Object)`.
     *
     * Volá se na daemon vlákně s tvrdým časovým limitem – diarizace nesmí nikdy zablokovat
     * přepis. Když modely chybí nebo se nestihne, vrací null a použije se heuristika.
     */
    private fun sherpaDiarize(floatSamples: FloatArray): List<SegmentInfo>? {
        val dir = speakerModelDir() ?: run {
            Log.i(TAG, "[SttEngine] Diarizace: modely mluvčích nenalezeny, používám akustickou heuristiku.")
            return null
        }

        val durationMs = floatSamples.size * 1000L / 16000L
        // Optimalizace odezvy a výkonu telefonu:
        // Neuronová segmentace Pyannote na CPU je výpočetně náročná.
        // Pro nahrávky delší než 30 s používáme okamžitou VAD + akustickou diarizaci (15 ms),
        // aby přepis začal okamžitě bez čekání a nezpůsoboval přehřívání procesoru.
        if (durationMs > 30000L) {
            Log.i(TAG, "[SttEngine] Nahrávka má ${durationMs} ms (> 30 s). Používám bleskovou VAD diarizaci pro okamžitou odezvu.")
            return null
        }
        val budgetMs = 5000L
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "galaxyai-speaker-diarizer").apply { isDaemon = true }
        }
        var future: java.util.concurrent.Future<List<SegmentInfo>?>? = null
        return try {
            future = executor.submit(java.util.concurrent.Callable {
                val segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                        model = File(dir, SPEAKER_SEGMENTATION_FILE).absolutePath
                    ),
                    numThreads = decoderThreads(),
                    provider = "cpu"
                )
                val embedding = SpeakerEmbeddingExtractorConfig(
                    model = File(dir, SPEAKER_EMBEDDING_FILE).absolutePath,
                    numThreads = decoderThreads(),
                    provider = "cpu"
                )
                val config = OfflineSpeakerDiarizationConfig(
                    segmentation = segmentation,
                    embedding = embedding,
                    clustering = FastClusteringConfig(
                        numClusters = -1,          // počet mluvčích se má určit sám
                        threshold = 0.5f,
                        computeConfidence = true
                    ),
                    minDurationOn = 0.3f,
                    minDurationOff = 0.5f
                )

                val startedAt = System.currentTimeMillis()
                val diarizer = OfflineSpeakerDiarization(config = config)
                val segments = try {
                    diarizer.process(floatSamples)
                } finally {
                    diarizer.release()
                }
                Log.i(TAG, "[SttEngine] Diarizace sherpa: ${segments.size} úseků za ${System.currentTimeMillis() - startedAt} ms.")
                segments.map { SegmentInfo((it.start * 1000).toInt(), (it.end * 1000).toInt(), it.speaker) }
                    .filter { it.endMs > it.startMs }
                    .sortedBy { it.startMs }
            })
            val result = future.get(budgetMs, TimeUnit.MILLISECONDS)
            val limited = result?.let { limitSpeakers(it) }
            if (limited.isNullOrEmpty()) null else limited
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "[SttEngine] Diarizace sherpa nedoběhla do ${budgetMs} ms, používám akustickou heuristiku.")
            null
        } catch (t: Throwable) {
            val cause = if (t is java.util.concurrent.ExecutionException) t.cause ?: t else t
            Log.w(TAG, "[SttEngine] Diarizace sherpa selhala (${cause.javaClass.simpleName}: ${cause.message}), používám akustickou heuristiku.")
            null
        } finally {
            try { future?.cancel(true) } catch (_: Throwable) {}
            executor.shutdownNow()
        }
    }

    /**
     * Sherpa clustering s `numClusters = -1` vytvoří na šumu desítky „mluvčích“
     * (naměřeno 41 na 6minutovém hovoru). Takové segmenty se pak nedají slévat do
     * vět – vznikne 111 krátkých oken, což je pomalé a přepis se drobí.
     *
     * Necháme jen [maxSpeakers] nejvýznamnějších (podle celkové délky řeči),
     * ostatní přimkneme k časově nejbližšímu zachovanému mluvčímu, slijeme
     * sousední segmenty téhož mluvčího a očíslujeme je 0..N-1 podle prvního
     * výskytu (Diktafon zobrazuje `speakerId + 1`).
     */
    private fun limitSpeakers(segments: List<SegmentInfo>, maxSpeakers: Int = MAX_SPEAKERS): List<SegmentInfo> {
        if (segments.isEmpty()) return segments

        val durations = HashMap<Int, Int>()
        for (s in segments) {
            durations[s.speakerId] = (durations[s.speakerId] ?: 0) + (s.endMs - s.startMs)
        }
        if (durations.size <= maxSpeakers) return renumberAndMerge(segments)

        val keep = durations.entries
            .sortedByDescending { it.value }
            .take(maxSpeakers)
            .map { it.key }
            .toSet()

        var lastKept = -1
        val remapped = segments.map { s ->
            if (s.speakerId in keep) {
                lastKept = s.speakerId
                s
            } else {
                SegmentInfo(s.startMs, s.endMs, if (lastKept >= 0) lastKept else keep.first())
            }
        }

        val merged = renumberAndMerge(remapped)
        Log.i(
            TAG,
            "[SttEngine] Mluvčí omezeni na $maxSpeakers (původně ${durations.size}), ${segments.size} → ${merged.size} úseků."
        )
        return merged
    }

    /** Slije sousední segmenty stejného mluvčího a přečísluje mluvčí 0..N-1. */
    private fun renumberAndMerge(segments: List<SegmentInfo>): List<SegmentInfo> {
        val idMap = HashMap<Int, Int>()
        val merged = ArrayList<SegmentInfo>(segments.size)
        for (s in segments) {
            val newId = idMap.getOrPut(s.speakerId) { idMap.size }
            val prev = merged.lastOrNull()
            if (prev != null && prev.speakerId == newId) {
                merged[merged.size - 1] = SegmentInfo(prev.startMs, s.endMs, newId)
            } else {
                merged.add(SegmentInfo(s.startMs, s.endMs, newId))
            }
        }
        return merged
    }

    /**
     * Najde složku s modely pro diarizaci. Musí to být media složka – proces `:asr`
     * IntelliVoiceServices na soubory v /storage/emulated/0/GalaxyAI nedosáhne.
     */
    private fun speakerModelDir(): File? {
        val candidates = listOf(
            File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI/models/speaker"),
            File("/storage/emulated/0/GalaxyAI/models/speaker")
        )
        return candidates.firstOrNull { dir ->
            File(dir, SPEAKER_SEGMENTATION_FILE).let { it.exists() && it.length() > 100_000 } &&
                File(dir, SPEAKER_EMBEDDING_FILE).let { it.exists() && it.length() > 100_000 }
        }
    }

    /**
     * Ošetří segmenty cizího (Samsung) diarizéru, aby se nikdy nerozbil dekódovací cyklus:
     *  - zahodí nesmysly (end <= start) a segmenty mimo nahrávku,
     *  - odhadne jednotky: pokud konce přesahují délku nahrávky, jde nejspíš o vzorky
     *    (16 kHz), ne o milisekundy – převede je,
     *  - ořízne na délku nahrávky a rozdělí okna delší než 30 s (Whisper swift sám
     *    doplňuje jen 30s kontext, delší okno by se tiše zkrátilo).
     */
    private fun normalizeExternalSegments(
        segments: List<SegmentInfo>,
        totalDurationMs: Int,
        maxWindowMs: Int = 30000
    ): List<SegmentInfo> {
        if (totalDurationMs <= 0) return emptyList()

        val looksLikeSamples = segments.maxOfOrNull { it.endMs } ?: 0 > totalDurationMs * 2
        val divisor = if (looksLikeSamples) 16 else 1
        if (looksLikeSamples) {
            Log.i(TAG, "[SttEngine] Externí segmenty vypadají jako vzorky, převádím na ms (÷16).")
        }

        val result = mutableListOf<SegmentInfo>()
        for (seg in segments) {
            var start = seg.startMs / divisor
            var end = seg.endMs / divisor
            if (start < 0) start = 0
            if (end > totalDurationMs) end = totalDurationMs
            if (end - start < 200) continue
            // Rozdělení příliš dlouhých oken, aby se vešla do Whisper kontextu.
            var windowStart = start
            while (windowStart < end) {
                val windowEnd = Math.min(windowStart + maxWindowMs, end)
                if (windowEnd - windowStart >= 200) {
                    result.add(SegmentInfo(windowStart, windowEnd, seg.speakerId))
                }
                windowStart = windowEnd
            }
        }
        return result.sortedBy { it.startMs }
    }

    /**
     * Zpracuje surová PCM data (16 kHz, 16-bit mono) a provede přesný přepis se synchronizovaným časováním a mluvčími.
     */
    fun transcribe(
        pcmBytes: ByteArray,
        localeTag: String = "cs-CZ",
        externalSegments: List<SegmentInfo>? = null,
        isVoicemail: Boolean = false,
        isCall: Boolean = false
    ): SttResult {
        val langInfo = ModuleConfig.getLanguageByLocale(localeTag)
        val whisperLang = langInfo?.whisperCode ?: localeTag.substringBefore("-").substringBefore("_").lowercase().takeIf { it.isNotBlank() } ?: "cs"

        val sampleCount = pcmBytes.size / 2
        val floatSamples = FloatArray(sampleCount)
        val byteBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)

        var maxAbs = 0f
        for (i in 0 until sampleCount) {
            val shortVal = byteBuffer.short
            val sample = shortVal / 32768.0f
            floatSamples[i] = sample
            val abs = Math.abs(sample)
            if (abs > maxAbs) maxAbs = abs
        }

        val totalDurationMs = (sampleCount * 1000L / 16000L).toInt()
        Log.i(TAG, "[SttEngine] Celková délka audia: ${totalDurationMs} ms (${sampleCount} vzorků), maxPeak: $maxAbs, isVoicemail=$isVoicemail, isCall=$isCall")

        // Auto-gain pro tiché nahrávky
        if (maxAbs in 0.005f..0.35f) {
            val gain = 0.8f / maxAbs
            Log.i(TAG, "[SttEngine] Aplikuji auto-gain: ${String.format(Locale.US, "%.2f", gain)}x")
            for (i in 0 until sampleCount) {
                floatSamples[i] = (floatSamples[i] * gain).coerceIn(-1.0f, 1.0f)
            }
        }

        val rec = getOrInitRecognizer(whisperLang)
        if (rec == null) {
            Log.w(TAG, "[SttEngine] OfflineRecognizer nebyl nalezen. Používám záložní výsledek.")
            return buildFallbackResult(totalDurationMs, langInfo?.displayName ?: "zvoleného jazyka", isModelMissing = true)
        }

        // Hlasová schránka je z definice monolog jednoho volajícího (Mluvčí 1).
        // Krátké nahrávky do 25 sekund, které nejsou telefonním hovorem, jsou rovněž typicky monolog (hlasová poznámka/diktát).
        val isSingleSpeaker = isVoicemail || (!isCall && totalDurationMs <= 25000L)

        // Získání segmentů řeči:
        // Buď jednomluvčí okno (hlasová schránka/poznámka), externí diarizér, nebo interní VAD + akustická diarizace
        val targetSegments: List<SegmentInfo> = if (isSingleSpeaker) {
            Log.i(TAG, "[SttEngine] Jednomluvčí režim (isVoicemail=$isVoicemail, isCall=$isCall, délka=${totalDurationMs} ms) -> celistvé okno s 1 mluvčím.")
            buildMonologueWindows(totalDurationMs, floatSamples, 16000, maxWindowMs = 28000)
        } else if (!externalSegments.isNullOrEmpty()) {
            val normalized = normalizeExternalSegments(externalSegments, totalDurationMs)
            if (normalized.isEmpty()) {
                Log.w(TAG, "[SttEngine] Externí diarizér nevrátil použitelné segmenty, počítám vlastní VAD.")
                internalSegments(floatSamples)
            } else {
                Log.i(TAG, "[SttEngine] Používám ${normalized.size} segmentů z externího (Samsung) diarizéru.")
                normalized
            }
        } else {
            internalSegments(floatSamples)
        }

        val recognizedSentences = mutableListOf<String>()
        val resultSegments = mutableListOf<SegmentInfo>()
        val allWords = mutableListOf<WordSegment>()

        for ((idx, seg) in targetSegments.withIndex()) {
            val startSample = ((seg.startMs * 16000L) / 1000L).toInt().coerceIn(0, sampleCount)
            val endSample = ((seg.endMs * 16000L) / 1000L).toInt().coerceIn(startSample, sampleCount)
            val chunkLen = endSample - startSample
            if (chunkLen < 1600) continue // Ignorovat segmenty kratší než 100 ms

            val chunkSamples = FloatArray(chunkLen)
            System.arraycopy(floatSamples, startSample, chunkSamples, 0, chunkLen)

            try {
                val stream = rec.createStream()
                stream.acceptWaveform(chunkSamples, 16000)
                rec.decode(stream)
                val res = rec.getResult(stream)
                stream.release()

                val segText = res.text.trim()
                if (segText.isEmpty()) continue

                Log.i(TAG, "[SttEngine] Okno $idx [Mluvčí ${seg.speakerId}] [${seg.startMs}..${seg.endMs} ms]: \"$segText\"")

                // Slova a jejich časování z token timestampů Whisperu (přesnější než rovnoměrné dělení)
                val timedWords = extractTimedWords(res, seg.startMs, seg.endMs)

                // Rozdělení okna na věty (bubliny) podle interpunkce a delších mezer
                val sentences = splitIntoSentences(timedWords)
                if (sentences.isEmpty()) {
                    recognizedSentences.add(segText)
                    resultSegments.add(seg)
                    continue
                }

                for (sentence in sentences) {
                    recognizedSentences.add(sentence.joinToString(" ") { it.text })
                    resultSegments.add(
                        SegmentInfo(
                            startMs = sentence.first().startMs,
                            endMs = sentence.last().endMs,
                            speakerId = seg.speakerId
                        )
                    )
                    for (w in sentence) allWords.add(WordSegment(w.text, w.startMs, w.endMs))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "[SttEngine] Chyba při dekódování okna $idx: ${t.message}", t)
            }
        }

        // Pokud žádný segment nevrátil text, zkusíme celé audio najednou
        if (recognizedSentences.isEmpty()) {
            try {
                Log.w(TAG, "[SttEngine] Segmenty vrátily prázdný text, zkouším celé audio najednou...")
                val stream = rec.createStream()
                stream.acceptWaveform(floatSamples, 16000)
                rec.decode(stream)
                val res = rec.getResult(stream)
                stream.release()

                val text = res.text.trim()
                if (text.isNotEmpty()) {
                    recognizedSentences.add(text)
                    resultSegments.add(SegmentInfo(0, totalDurationMs, 0))
                    val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
                    val wordDur = if (words.isNotEmpty()) totalDurationMs / words.size else 100
                    for (i in words.indices) {
                        val s = i * wordDur
                        val e = if (i == words.size - 1) totalDurationMs else (i + 1) * wordDur
                        allWords.add(WordSegment(words[i], s, e))
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "[SttEngine] Chyba při celkovém dekódování: ${t.message}", t)
            }
        }

        if (recognizedSentences.isEmpty()) {
            return buildFallbackResult(totalDurationMs, langInfo?.displayName ?: "zvoleného jazyka", isModelMissing = false)
        }

        val fullText = recognizedSentences.joinToString(" ")
        val timingInfo = IntArray(allWords.size * 2)
        for (i in allWords.indices) {
            // Samsung Voice Recorder multiplies each timing_info value by 10 in AiWordItemHandler.getWords:
            // (mul-long v2, 10). Therefore, timingInfo values MUST be divided by 10 (centiseconds/10ms units)!
            timingInfo[i * 2] = allWords[i].startMs / 10
            timingInfo[i * 2 + 1] = allWords[i].endMs / 10
        }

        Log.i(TAG, "[SttEngine] Přepis kompletní: ${recognizedSentences.size} vět, ${allWords.size} slov, ${resultSegments.map { it.speakerId }.distinct().size} mluvčích: \"$fullText\"")

        return SttResult(
            text = fullText,
            sentences = recognizedSentences,
            segments = resultSegments,
            words = allWords,
            timingInfo = timingInfo
        )
    }

    private fun buildFallbackResult(durationMs: Int, langName: String, isModelMissing: Boolean = false): SttResult {
        val text = if (isModelMissing) {
            "Přepis pro $langName se nezdařil. Zkontrolujte stažení modelu v aplikaci."
        } else {
            "[Nebylo rozpoznáno žádné mluvené slovo]"
        }
        val splitWords = text.split(" ")
        val wordDuration = if (splitWords.isNotEmpty()) durationMs / splitWords.size else 100
        val words = mutableListOf<WordSegment>()

        for (i in splitWords.indices) {
            val start = i * wordDuration
            val end = (i + 1) * wordDuration
            words.add(WordSegment(splitWords[i], start, end))
        }

        val timingInfo = IntArray(words.size * 2)
        for (i in words.indices) {
            timingInfo[i * 2] = words[i].startMs / 10
            timingInfo[i * 2 + 1] = words[i].endMs / 10
        }

        return SttResult(
            text = text,
            sentences = listOf(text),
            segments = listOf(SegmentInfo(0, durationMs, 0)),
            words = words,
            timingInfo = timingInfo
        )
    }
}
