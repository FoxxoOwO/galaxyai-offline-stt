package cz.galaxyai.lsposed.hooks

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import cz.galaxyai.lsposed.MainHook.Companion.TAG
import cz.galaxyai.lsposed.config.ModuleConfig
import cz.galaxyai.lsposed.stt.SttEngineManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object IntelliVoiceHook {

    private const val CLS_PIPE_USER_CHANNEL =
        "com.samsung.android.intellivoiceservice.asr.service.PipeUserChannel"
    private const val CLS_RECOGNITION_LISTENER =
        "com.samsung.android.sivs.ai.sdkcommon.asr.IRecognitionListener"

    private val sttScope = CoroutineScope(Dispatchers.IO)
    private var serviceClassLoader: ClassLoader? = null

    /**
     * Jazyky, které Samsung přepisuje sám. Do těchto cest modul nikdy nezasahuje
     * (rozhodnutí 2026-11-06), ale zároveň jim musí vypnout Samsungí diarizaci,
     * protože její nativní knihovna na tomto zařízení chybí (viz `prepare()`).
     */
    private val OFFICIAL_SAMSUNG_LANGS = listOf(
        "en", "de", "fr", "es", "it", "pt", "pl", "ru", "tr",
        "ro", "sv", "nl", "zh", "ja", "ko", "th", "vi", "hi", "id", "ar"
    )

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        // IntelliVoice Hook se týká výhradně ASR (rozpoznávání řeči).
        // Do procesu překladu (:translation) nikdy nezasahujeme, aby nedocházelo k pádům.
        if (lpparam.processName.endsWith(":translation")) {
            return
        }

        serviceClassLoader = lpparam.classLoader
        mirrorConfigToPrivateStorage()
        hookRuntimeLoadLibrary()
        hookPipeUserChannel(lpparam.classLoader)
    }

    /**
     * Procesy IntelliVoice Services (main, :translation, :asr) běží pod jedním UID, ale
     * `:asr` nemá přístup k /storage/emulated/0 → nemůže si přečíst config.json
     * (exists=true, canRead=false) a vždy spadl na výchozí nastavení.
     *
     * Proto při startu kteréhokoli procesu IVS zkopírujeme config do privátního
     * úložiště aplikace, které je pro všechny její procesy čitelné. Děláme to hned
     * v [init], aby zrcadlo existovalo dřív, než se :asr začne ptát.
     */
    private fun mirrorConfigToPrivateStorage() {
        try {
            val ctx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context ?: run {
                Log.w(TAG, "[IntelliVoice] Zrcadlení configu: Context není k dispozici")
                return
            }
            ModuleConfig.mirrorToPrivateStorage(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "[IntelliVoice] Zrcadlení configu selhalo: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun hookRuntimeLoadLibrary() {
        try {
            for (method in Runtime::class.java.declaredMethods) {
                if (method.name == "loadLibrary0") {
                    de.robv.android.xposed.XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            for (arg in param.args) {
                                if (arg == "sherpa-onnx-jni") {
                                    param.result = null
                                    return
                                }
                            }
                        }
                    })
                }
            }
            for (method in System::class.java.declaredMethods) {
                if (method.name == "loadLibrary") {
                    de.robv.android.xposed.XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (param.args.getOrNull(0) == "sherpa-onnx-jni") {
                                param.result = null
                            }
                        }
                    })
                }
            }
            Log.i(TAG, "[IntelliVoice] Hook na Runtime/System loadLibrary úspěšně nastaven pro sherpa-onnx-jni.")
        } catch (t: Throwable) {
            Log.w(TAG, "[IntelliVoice] Hook na Runtime/System loadLibrary selhal: ${t.message}")
        }
    }

    private fun hookPipeUserChannel(classLoader: ClassLoader) {
        try {
            val pipeUserChannelClass = XposedHelpers.findClass(CLS_PIPE_USER_CHANNEL, classLoader)
            val recognitionListenerClass = XposedHelpers.findClass(CLS_RECOGNITION_LISTENER, classLoader)

            // 1. Hook na metodu prepare(Bundle) - zjištění vybraného jazyka
            XposedHelpers.findAndHookMethod(
                pipeUserChannelClass,
                "prepare",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val bundle = param.args[0] as? Bundle ?: return
                        val config = ModuleConfig.load()
                        var detectedLocale: String? = null

                        for (key in bundle.keySet()) {
                            Log.i(TAG, "[IntelliVoice] prepare bundle[$key] = ${bundle.get(key)}")
                        }

                        // Samsungí diarizér řešíme až po detekci jazyka (viz níže) – u oficiálních
                        // jazyků ho vypínáme, u našich necháváme zapnutý, aby Diktafon použil DialogInfo.

                        // 1. Zjištění z objektu locale
                        val localeObj = bundle.get("locale")
                        if (localeObj is Locale) {
                            val tag = localeObj.toLanguageTag()
                            if (tag != "und" && tag.isNotBlank()) {
                                detectedLocale = tag
                            } else if (localeObj.language.isNotBlank() && localeObj.language != "und") {
                                detectedLocale = localeObj.language
                            }
                        } else if (localeObj is String && localeObj.isNotBlank() && localeObj != "und") {
                            detectedLocale = localeObj
                        }

                        // 2. Kontrola dalších možných klíčů v Bundle
                        if (detectedLocale.isNullOrBlank() || detectedLocale == "und") {
                            for (k in listOf("target_locale", "language", "selected_language", "languageTag", "locale_string", "language_code", "primaryLocale")) {
                                val v = bundle.getString(k)
                                if (!v.isNullOrBlank() && v != "und") {
                                    detectedLocale = v
                                    break
                                }
                            }
                        }

                        // 3. Kontrola preferredLocale seznamu:
                        // VoiceNote posílá seznam dostupných jazyků, kde na prvním místě bývá systémový jazyk Samsungu (např. en-US).
                        // Pokud byl locale prázdný, hledáme v preferredLocale přednostně náš povolený jazyk (např. cs-CZ, sk-SK...).
                        if (detectedLocale.isNullOrBlank() || detectedLocale == "und") {
                            val pref = bundle.get("preferredLocale")
                            if (pref is List<*>) {
                                val prefTags = pref.mapNotNull { item ->
                                    when (item) {
                                        is Locale -> if (item.toLanguageTag() != "und") item.toLanguageTag() else item.toString()
                                        is String -> item
                                        else -> null
                                    }
                                }.filter { it.isNotBlank() && it != "und" }

                                // Hledáme shodný jazyk s naší konfigurací
                                val ourMatch = prefTags.find { tag ->
                                    config.enabledLocales.any { ourLoc ->
                                        tag.equals(ourLoc, ignoreCase = true) ||
                                        tag.startsWith(ourLoc.substringBefore("-"), ignoreCase = true) ||
                                        ourLoc.startsWith(tag.substringBefore("-"), ignoreCase = true)
                                    }
                                }

                                if (ourMatch != null) {
                                    detectedLocale = config.enabledLocales.find {
                                        it.equals(ourMatch, ignoreCase = true) ||
                                        it.startsWith(ourMatch.substringBefore("-"), ignoreCase = true) ||
                                        ourMatch.startsWith(it.substringBefore("-"), ignoreCase = true)
                                    } ?: ourMatch
                                    Log.i(TAG, "[IntelliVoice] V preferredLocale nalezen náš povolený jazyk: $detectedLocale")
                                } else {
                                    detectedLocale = prefTags.firstOrNull()
                                }
                            }
                        }

                        // Pokud byl jazyk stále neznámý, prázdný nebo "und", znamená to, že VoiceNote
                        // vybral náš přidaný jazyk (který Samsung nativně nezná).
                        // V takovém případě použijeme první povolený jazyk z naší konfigurace (např. cs-CZ)
                        if (detectedLocale.isNullOrBlank() || detectedLocale == "und") {
                            detectedLocale = config.enabledLocales.firstOrNull() ?: "cs-CZ"
                            Log.i(TAG, "[IntelliVoice] Jazyk v bundle byl neznámý/prázdný, přiřazuji aktivní přidaný jazyk: $detectedLocale")
                        } else {
                            Log.i(TAG, "[IntelliVoice] PipeUserChannel.prepare finálně detekován jazyk: $detectedLocale")
                        }
                        // Samsungí diarizér (com.samsung.android.ai.sd.SpeakerDiarization) nemá na tomto
                        // zařízení nativní knihovnu – při loadu padá na UnsatisfiedLinkError:
                        //   Failed to load native library / No implementation found for
                        //   void com.samsung.android.ai.sd.SpeakerDiarization.native_setup(java.lang.Object)
                        // VoiceNote přitom posílá enable_speaker_diarisation=true, čímž se rozjede
                        // RushAndCache s SD a úloha se NIKDY nedokončí -> „Přepisování… 0 %“
                        // (i pro oficiální jazyky Samsungu, které s vypnutým modulem fungují).
                        //
                        // Vypínáme ho ale jen u oficiálních jazyků – u našich vlastních potřebuje
                        // Diktafon příznak ZAPNUTÝ, jinak ignoruje naše DialogInfo a vypíše
                        // u všech bublin „Mluvčí 0“.
                        val isOfficialRequest = OFFICIAL_SAMSUNG_LANGS.any {
                            detectedLocale.startsWith(it, ignoreCase = true)
                        }
                        if (isOfficialRequest) {
                            Log.i(TAG, "[IntelliVoice] PipeUserChannel.prepare: Oficiální jazyk Samsungu ($detectedLocale), ponechávám bundle beze změny.")
                        }

                        XposedHelpers.setAdditionalInstanceField(param.thisObject, "requested_locale", detectedLocale)
                    }
                }
            )

            // 2. Hook na metodu write(ParcelFileDescriptor, IRecognitionListener)
            XposedHelpers.findAndHookMethod(
                pipeUserChannelClass,
                "write",
                ParcelFileDescriptor::class.java,
                recognitionListenerClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pfd = param.args[0] as? ParcelFileDescriptor ?: return
                        val listener = param.args[1] ?: return
                        val requestedLocale = XposedHelpers.getAdditionalInstanceField(param.thisObject, "requested_locale") as? String

                        val config = ModuleConfig.load()
                        val req = requestedLocale?.replace("_", "-") ?: ""
                        val matchedConfigLocale = config.enabledLocales.find {
                            it.equals(req, ignoreCase = true) ||
                            it.startsWith(req, ignoreCase = true) ||
                            (req.isNotEmpty() && req.startsWith(it.substringBefore("-"), ignoreCase = true))
                        }
                        val isOurLanguage = matchedConfigLocale != null

                        // Pokud uživatel vybral jazyk přímo v UI (např. cs-CZ, en-US, sk-SK...),
                        // obsloužíme jej naším Whisper modelem. Nativní engine Samsungu na tomto
                        // zařízení přepis nedokončí ani pro angličtinu a zůstává viset na 0%.
                        val systemLocale = ModuleConfig.systemLocaleTag()
                        val targetLocale = matchedConfigLocale
                            ?: (if (req.isNotBlank() && req != "und") req else null)
                            ?: config.fallbackLocale?.takeIf { it.isNotBlank() }
                                ?.let { raw -> ModuleConfig.normalizeLocaleTag(raw) }
                            ?: config.enabledLocales.find {
                                it.equals(systemLocale, ignoreCase = true) ||
                                it.substringBefore("-").equals(systemLocale.substringBefore("-"), ignoreCase = true)
                            }
                            ?: config.enabledLocales.firstOrNull() ?: "cs-CZ"
                        Log.i(TAG, "[IntelliVoice] PipeUserChannel.write ZACHYCENO! Cílový jazyk: $targetLocale (původní req: '$requestedLocale')")
                        Log.i(TAG, "[IntelliVoice] Přesměrovávám PCM stream do lokálního Whisper enginu...")

                        val pcmInputStream: InputStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)

                        sttScope.launch {
                            try {
                                processSpeechToText(pcmInputStream, listener, targetLocale)
                            } catch (e: Exception) {
                                Log.e(TAG, "[IntelliVoice] Chyba při přepisu: ", e)
                                notifyError(listener, 4, e.message ?: "Chyba lokálního STT")
                            }
                        }

                        param.result = true
                    }
                }
            )

            Log.i(TAG, "[IntelliVoice] Úspěšně zahookován PipeUserChannel.write")
        } catch (t: Throwable) {
            Log.e(TAG, "[IntelliVoice] Selhalo zahookování PipeUserChannel: ", t)
        }
    }

    private suspend fun processSpeechToText(inputStream: InputStream, listener: Any, localeTag: String) {
        // Čtení z pipe nesmí selhat na tom, že zapisující strana ukončila proud
        // (ParcelFileDescriptor vyhodí "Remote side is dead"). I tak může být
        // v bufferu celá nahrávka, proto čteme po částech a výjimku ukončuje jen
        // jako konec dat.
        val pcmBytes = readAllTolerant(inputStream)
        Log.i(TAG, "[IntelliVoice] Načteno ${pcmBytes.size} bytů audio dat pro jazyk $localeTag.")
        if (pcmBytes.size < 3200) { // méně než 100 ms při 16 kHz / 16 bit
            Log.w(TAG, "[IntelliVoice] Příliš málo audio dat (${pcmBytes.size} bytů), ruším přepis.")
            notifyError(listener, 4, "Nahrávka neobsahuje dostatek audio dat")
            return
        }

        // Uložení nahrávky pro diagnostiku výhradně do uživatelského úložiště.
        // Proces :asr nemá MANAGE_EXTERNAL_STORAGE, takže /storage/emulated/0/GalaxyAI
        // končí na EPERM – zkusíme i media složku a privátní cache.
        try {
            val candidates = listOf(
                File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI"),
                File("/storage/emulated/0/GalaxyAI"),
                File("/data/local/tmp/GalaxyAI")
            )
            for (dir in candidates) {
                try {
                    dir.mkdirs()
                    if (writeWavFile(File(dir, "last_recording.wav"), pcmBytes, 16000, 1)) break
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // Přepis s lokálním Whisper enginem (využívá sherpa-onnx / Pyannote pro diarizaci,
        // bez volání nestabilního Samsung diarizéru, který způsoboval pád služby)
        val sttResult = SttEngineManager.transcribe(pcmBytes, localeTag, null)

        Log.i(TAG, "[IntelliVoice] Přepis [$localeTag] dokončen: \"${sttResult.text}\" (${sttResult.words.size} slov v ${sttResult.sentences.size} segmentech, mluvčí: ${sttResult.segments.map { it.speakerId }.distinct()})")

        // 3. Vytvoření DialogInfo s opraveným pořadím parametrů: SpeechInfo(speakerId, startMs, endMs)
        val dialogInfo = serviceClassLoader?.let { createDialogInfo(it, sttResult.segments) }

        val resultBundle = Bundle().apply {
            putString("result", sttResult.text)
            putIntArray("timing_info", sttResult.timingInfo)
            putStringArrayList("immutable_sentences", ArrayList(sttResult.sentences))
            putStringArrayList("mutable_sentences", arrayListOf())
            putInt("RESULT_TYPE", 1)
            if (dialogInfo != null) {
                putParcelable("dialog_info", dialogInfo)
                putBoolean("is_last_dialog_info", true)
            }
        }

        XposedHelpers.callMethod(listener, "onResults", resultBundle)
        Log.i(TAG, "[IntelliVoice] onResults s přesným časováním a mluvčími úspěšně doručeno do volající aplikace!")
    }

    private fun createDialogInfo(classLoader: ClassLoader, segments: List<cz.galaxyai.lsposed.stt.SegmentInfo>): android.os.Parcelable? {
        return try {
            val speechInfoClass = XposedHelpers.findClass("com.samsung.android.sivs.ai.sdkcommon.asr.SpeechInfo", classLoader)
            val dialogInfoClass = XposedHelpers.findClass("com.samsung.android.sivs.ai.sdkcommon.asr.DialogInfo", classLoader)

            val distinctSpeakers = segments.map { it.speakerId }.distinct().sorted()
            val speakerSet = if (distinctSpeakers.isEmpty()) setOf(0) else distinctSpeakers.toSet()

            val speechInfoList = ArrayList<Any>()
            for (seg in segments) {
                // Pozor: Samsung SpeechInfo constructor je (int speaker, int startTime, int endTime)!
                // VoiceNote provádí v getSpeakerId: (SpeechInfo.getSpeaker() + 1).
                // Tedy pro "Mluvčí 1" musí být speaker 0, pro "Mluvčí 2" musí být speaker 1!
                // Přidáváme mírnou rezervu (50 ms), aby časové značky všech slov zaručeně padly do rozsahu bubliny
                val start = Math.max(0, seg.startMs - 50)
                val end = seg.endMs + 50
                val info = XposedHelpers.newInstance(speechInfoClass, seg.speakerId, start, end)
                speechInfoList.add(info)
            }

            val dialogInfo = try {
                XposedHelpers.newInstance(dialogInfoClass, speakerSet)
            } catch (_: Throwable) {
                XposedHelpers.newInstance(dialogInfoClass)
            }

            XposedHelpers.callMethod(dialogInfo, "setSpeechInfos", speechInfoList)
            try {
                XposedHelpers.callMethod(dialogInfo, "setSpeakerList", ArrayList(speakerSet))
            } catch (_: Throwable) {}

            Log.i(TAG, "[IntelliVoice] Vytvořeno DialogInfo s ${segments.size} bublinami řeči a mluvčími: $speakerSet.")
            dialogInfo as? android.os.Parcelable
        } catch (t: Throwable) {
            Log.w(TAG, "[IntelliVoice] Nelze vytvořit DialogInfo: ${t.message}", t)
            null
        }
    }

    /**
     * Načte celý proud, ale konec zápisu bere jako normální ukončení dat.
     * ParcelFileDescriptor.AutoCloseInputStream jinak vyhodí IOException
     * ("Remote side is dead") a zahodí i to, co už bylo načtené.
     */
    private fun readAllTolerant(inputStream: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream(1 shl 20)
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val read = inputStream.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[IntelliVoice] Čtení audio proudu ukončeno: ${e.javaClass.simpleName}: ${e.message} (načteno ${out.size()} bytů)")
        } finally {
            try { inputStream.close() } catch (_: Throwable) {}
        }
        return out.toByteArray()
    }

    private fun writeWavFile(file: File, pcmBytes: ByteArray, sampleRate: Int = 16000, channels: Int = 1): Boolean {
        try {
            val totalAudioLen = pcmBytes.size.toLong()
            val totalDataLen = totalAudioLen + 36
            val byteRate = (16 * sampleRate * channels / 8).toLong()
            val header = ByteArray(44)

            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1
            header[21] = 0
            header[22] = channels.toByte()
            header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = (channels * 16 / 8).toByte()
            header[33] = 0
            header[34] = 16
            header[35] = 0
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xff).toByte()
            header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
            header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
            header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

            file.parentFile?.mkdirs()
            file.outputStream().use { os ->
                os.write(header)
                os.write(pcmBytes)
            }
            Log.i(TAG, "[IntelliVoice] Uloženo WAV audio: ${file.absolutePath} (${file.length()} bytů)")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "[IntelliVoice] Nelze uložit WAV do ${file.absolutePath}: ${e.message}")
            return false
        }
    }

    private fun notifyError(listener: Any, code: Int, message: String) {
        try {
            val errorBundle = Bundle().apply {
                putInt("error_code", code)
                putString("error_message", message)
            }
            XposedHelpers.callMethod(listener, "onError", errorBundle)
        } catch (e: Exception) {
            Log.e(TAG, "[IntelliVoice] Selhalo odeslání chyby listeneru: ", e)
        }
    }
}
