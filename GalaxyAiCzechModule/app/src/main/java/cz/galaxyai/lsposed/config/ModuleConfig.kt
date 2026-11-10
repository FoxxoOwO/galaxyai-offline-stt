package cz.galaxyai.lsposed.config

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import cz.galaxyai.lsposed.MainHook.Companion.TAG
import java.io.File

data class SupportedLanguage(
    val localeTag: String,    // e.g. "cs-CZ"
    val whisperCode: String,  // e.g. "cs"
    val displayName: String,  // e.g. "Čeština"
    val nativeName: String,   // e.g. "Čeština"
    val englishName: String   // e.g. "Czech"
)

data class ModelInfo(
    val id: String,           // "turbo", "small", "base"
    val name: String,         // "Whisper Large-v3-Turbo", ...
    val sizeMb: Int,          // 1030, 370, 150
    val encoderUrl: String,
    val decoderUrl: String,
    val tokensUrl: String
)

data class ConfigData(
    var selectedModel: String = "turbo",
    var enabledLocales: MutableList<String> = mutableListOf("cs-CZ", "sk-SK", "uk-UA"),
    /**
     * Jazyk, který se použije, když VoiceNote nepošle žádný (prázdný locale) – tehdy
     * se bere jazyk systému, případně první povolený jazyk.
     *
     * Pozn.: dřívější přepínač `overrideOfficialLocale` byl odstraněn 2026-11-06.
     * Modul už nikdy nepřebírá oficiální jazyky Galaxy AI – viz IntelliVoiceHook.write().
     */
    var fallbackLocale: String? = null
)

object ModuleConfig {

    private val gson = Gson()

    /**
     * Seznam jazyků, které Galaxy AI oficiálně NEPODPORUJE, ale Whisper je plně zvládá.
     * Jazyky s oficiální podporou Galaxy AI (Polština, Ruština, Turečtina, Rumunština,
     * Švédština, Holandština, Angličtina, Němčina, atd.) sem nepatří, aby nedocházelo ke kolizím.
     */
    val ALL_SUPPORTED_LANGUAGES = listOf(
        SupportedLanguage("cs-CZ", "cs", "Čeština", "Čeština", "Czech"),
        SupportedLanguage("sk-SK", "sk", "Slovenčina", "Slovenčina", "Slovak"),
        SupportedLanguage("uk-UA", "uk", "Ukrajinština", "Українська", "Ukrainian"),
        SupportedLanguage("hu-HU", "hu", "Maďarština", "Magyar", "Hungarian"),
        SupportedLanguage("hr-HR", "hr", "Chorvatština", "Hrvatski", "Croatian"),
        SupportedLanguage("sl-SI", "sl", "Slovinština", "Slovenščina", "Slovenian"),
        SupportedLanguage("bg-BG", "bg", "Bulharština", "Български", "Bulgarian"),
        SupportedLanguage("el-GR", "el", "Řečtina", "Ελληνικά", "Greek"),
        SupportedLanguage("da-DK", "da", "Dánština", "Dansk", "Danish"),
        SupportedLanguage("fi-FI", "fi", "Finština", "Suomi", "Finnish"),
        SupportedLanguage("no-NO", "no", "Norština", "Norsk", "Norwegian"),
        SupportedLanguage("lt-LT", "lt", "Litevština", "Lietuvių", "Lithuanian"),
        SupportedLanguage("lv-LV", "lv", "Lotyština", "Latviešu", "Latvian"),
        SupportedLanguage("et-EE", "et", "Estonština", "Eesti", "Estonian")
    )

    val MODELS = listOf(
        ModelInfo(
            id = "turbo",
            name = "Whisper Large-v3-Turbo (800M)",
            sizeMb = 1030,
            encoderUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-encoder.int8.onnx",
            decoderUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-decoder.int8.onnx",
            tokensUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-tokens.txt"
        ),
        ModelInfo(
            id = "small",
            name = "Whisper Small (244M)",
            sizeMb = 370,
            encoderUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx",
            decoderUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx",
            tokensUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt"
        ),
        ModelInfo(
            id = "base",
            name = "Whisper Base (74M)",
            sizeMb = 150,
            encoderUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-encoder.int8.onnx",
            decoderUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-decoder.int8.onnx",
            tokensUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-tokens.txt"
        ),
        ModelInfo(
            id = "tiny",
            name = "Whisper Tiny (39M)",
            sizeMb = 75,
            encoderUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
            decoderUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
            tokensUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt"
        )
    )

    data class DiarizationModelInfo(
        val name: String = "Speaker Diarization Pack (Pyannote 3.0 + ERes2Net)",
        val sizeMb: Int = 45,
        val segmentationUrl: String = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx",
        val embeddingUrl: String = "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/main/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
    )

    val DIARIZATION_MODEL = DiarizationModelInfo()

    /**
     * Sdílené umístění v media adresáři IntelliVoice Services.
     *
     * Procesy IVS na /storage/emulated/0/GalaxyAI nedosáhnou (canRead=false), ale
     * do svého vlastního media adresáře ano – tam se už úspěšně ukládá diagnostický
     * WAV. Náš doprovodný app tam zapisuje díky MANAGE_EXTERNAL_STORAGE, takže je to
     * jediné místo čitelné oběma stranami bez rootu a bez ContentProvideru (ten je
     * pro IVS neviditelný kvůli package visibility na Androidu 11+).
     */
    private const val MEDIA_CONFIG_PATH =
        "/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI/config.json"

    private val configFiles = listOf(
        File(MEDIA_CONFIG_PATH),
        File("/storage/emulated/0/GalaxyAI/config.json"),
        File("/sdcard/GalaxyAI/config.json")
    )

    /**
     * Cached parse of the config file. [load] is called from inside hooked methods
     * (language list building in VoiceNote), i.e. potentially on the UI thread and
     * many times per screen — reading + parsing the JSON on every call was measurable
     * disk I/O. The cache is validated against path + mtime + size, so an edit made
     * by the companion UI app is picked up on the next call.
     */
    private data class CacheEntry(
        val path: String,
        val lastModified: Long,
        val length: Long,
        val data: ConfigData
    )

    @Volatile
    private var cacheEntry: CacheEntry? = null

    /**
     * Fallback pro procesy, které nemají přístup k /storage/emulated/0.
     *
     * Proces `:asr` služby IntelliVoice Services běží pod jiným UID (u0_a135) a soubor
     * config.json (0660, vlastník náš app) tam je `exists=true`, ale `canRead()=false`.
     * Bez tohoto fallbacku se v tom procesu vždy použily výchozí hodnoty a uživatelovo
     * nastavení modelu a jazyků se při skutečném přepisu ignorovalo.
     * Provider běží v našem procesu (ten oprávnění má), takže soubor otevře za nás.
     */
    private const val PROVIDER_URI = "content://cz.galaxyai.lsposed.modelprovider/config"
    private const val PROVIDER_CACHE_MS = 10_000L
    private const val PROVIDER_RETRY_MS = 10_000L

    /** Soubor v privátním úložišti aplikace, který sdílí všechny procesy IntelliVoice. */
    private const val PRIVATE_CONFIG_NAME = "galaxyai_config.json"

    @Volatile
    private var providerCache: ConfigData? = null

    @Volatile
    private var providerCacheAt = 0L

    @Volatile
    private var providerFailureAt = 0L

    private fun appContext(): Context? {
        val attempts = listOf(
            "android.app.ActivityThread" to "currentApplication",
            "android.app.AppGlobals" to "getInitialApplication"
        )
        for ((clsName, method) in attempts) {
            try {
                val cls = Class.forName(clsName)
                val ctx = cls.getMethod(method).invoke(null) as? Context
                if (ctx != null) return ctx
            } catch (t: Throwable) {
                Log.w(TAG, "[Config] ${clsName}.${method}() selhal: ${t.javaClass.simpleName}")
            }
        }
        return null
    }

    private fun loadFromProvider(): ConfigData? {
        val now = SystemClock.elapsedRealtime()
        val cached = providerCache
        if (cached != null && now - providerCacheAt < PROVIDER_CACHE_MS) {
            return cached.copy(enabledLocales = cached.enabledLocales.toMutableList())
        }
        if (now - providerFailureAt < PROVIDER_RETRY_MS) return cached

        return try {
            val ctx = appContext()
            if (ctx == null) {
                providerFailureAt = now
                Log.w(TAG, "[Config] Nelze získat Context pro ContentProvider – používám výchozí nastavení")
                return cached
            }
            val json = ctx.contentResolver.openInputStream(Uri.parse(PROVIDER_URI))
                ?.use { it.bufferedReader().readText() }
            if (json.isNullOrBlank()) {
                providerFailureAt = now
                return cached
            }
            val parsed = gson.fromJson(json, ConfigData::class.java)
            val locales = parsed?.enabledLocales
            if (parsed == null || locales.isNullOrEmpty()) {
                providerFailureAt = now
                return cached
            }
            parsed.enabledLocales = locales
            providerCache = parsed
            providerCacheAt = now
            Log.i(
                TAG,
                "[Config] Načteno přes ContentProvider: model=${parsed.selectedModel}, " +
                    "jazyky=${locales.joinToString(",")}"
            )
            parsed.copy(enabledLocales = locales.toMutableList())
        } catch (t: Throwable) {
            providerFailureAt = now
            Log.w(TAG, "[Config] ContentProvider nedostupný: ${t.javaClass.simpleName}: ${t.message}")
            cached
        }
    }

    /**
     * Zkopíruje config.json z veřejného úložiště do privátního adresáře aplikace.
     *
     * Volá se z hooku v procesu IntelliVoice Services (main/:translation/:asr mají
     * stejné UID). `:asr` totiž na /storage/emulated/0 nedosáhne, takže si jinak
     * nikdy nepřečte uživatelské nastavení a používá výchozí hodnoty.
     */
    fun mirrorToPrivateStorage(ctx: Context) {
        val src = configFiles.firstOrNull {
            try {
                it.exists() && it.canRead()
            } catch (t: Throwable) {
                false
            }
        }
        if (src == null) {
            Log.w(TAG, "[Config] Zrcadlení: zdrojový config není čitelný, přeskakuji")
            return
        }
        if (src.absolutePath == MEDIA_CONFIG_PATH) {
            Log.i(TAG, "[Config] Config už je ve sdíleném media adresáři, zrcadlení není potřeba")
            return
        }
        try {
            val bytes = src.readBytes()
            val dst = File(ctx.filesDir, PRIVATE_CONFIG_NAME)
            if (dst.exists() && dst.length() == bytes.size.toLong()) {
                val current = dst.readBytes()
                if (current.contentEquals(bytes)) return
            }
            dst.writeBytes(bytes)
            cacheEntry = null
            Log.i(TAG, "[Config] Zrcadlím config do ${dst.absolutePath} (${bytes.size} B) ze ${src.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "[Config] Zrcadlení configu selhalo: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun load(): ConfigData {
        for (f in configFiles) {
            val readable = try {
                f.exists() && f.canRead()
            } catch (t: Throwable) {
                false
            }
            if (!readable) {
                Log.w(TAG, "[Config] ${f.absolutePath} není čitelný (exists=${f.exists()}), zkouším další cestu.")
                continue
            }

            val lastModified = try {
                f.lastModified()
            } catch (t: Throwable) {
                0L
            }
            val length = try {
                f.length()
            } catch (t: Throwable) {
                -1L
            }

            val cached = cacheEntry
            if (cached != null &&
                cached.path == f.absolutePath &&
                cached.lastModified == lastModified &&
                cached.length == length
            ) {
                return cached.data.copy(enabledLocales = cached.data.enabledLocales.toMutableList())
            }

            try {
                val json = f.readText()
                val parsed = gson.fromJson(json, ConfigData::class.java)
                val locales: MutableList<String>? = parsed?.enabledLocales
                if (parsed != null && !locales.isNullOrEmpty()) {
                    parsed.enabledLocales = locales
                    cacheEntry = CacheEntry(f.absolutePath, lastModified, length, parsed)
                    Log.i(
                        TAG,
                        "[Config] Načteno z ${f.absolutePath}: model=${parsed.selectedModel}, " +
                            "jazyky=${locales.joinToString(",")}"
                    )
                    return parsed.copy(enabledLocales = locales.toMutableList())
                }
                Log.w(TAG, "[Config] ${f.absolutePath} neobsahuje enabledLocales, přeskakuji.")
            } catch (t: Throwable) {
                Log.w(TAG, "[Config] Error reading ${f.absolutePath}: ${t.message}")
            }
        }
        loadFromPrivateMirror()?.let { return it }
        return loadFromProvider() ?: ConfigData()
    }

    /**
     * Čte zrcadlo vytvořené [mirrorToPrivateStorage] – používá se v procesech, které
     * nemají přístup k /storage/emulated/0 (typicky `:asr` IntelliVoice Services).
     */
    private fun loadFromPrivateMirror(): ConfigData? {
        val ctx = appContext() ?: return null
        val f = File(ctx.filesDir, PRIVATE_CONFIG_NAME)
        val readable = try {
            f.exists() && f.canRead()
        } catch (t: Throwable) {
            false
        }
        if (!readable) {
            Log.w(TAG, "[Config] Privátní zrcadlo ${f.absolutePath} neexistuje")
            return null
        }
        val lastModified = f.lastModified()
        val length = f.length()
        val cached = cacheEntry
        if (cached != null && cached.path == f.absolutePath &&
            cached.lastModified == lastModified && cached.length == length
        ) {
            return cached.data.copy(enabledLocales = cached.data.enabledLocales.toMutableList())
        }
        return try {
            val parsed = gson.fromJson(f.readText(), ConfigData::class.java)
            val locales = parsed?.enabledLocales
            if (parsed == null || locales.isNullOrEmpty()) return null
            parsed.enabledLocales = locales
            cacheEntry = CacheEntry(f.absolutePath, lastModified, length, parsed)
            Log.i(
                TAG,
                "[Config] Načteno z privátního zrcadla: model=${parsed.selectedModel}, " +
                    "jazyky=${locales.joinToString(",")}"
            )
            parsed.copy(enabledLocales = locales.toMutableList())
        } catch (t: Throwable) {
            Log.w(TAG, "[Config] Chyba čtení zrcadla: ${t.message}")
            null
        }
    }

    fun save(config: ConfigData) {
        cacheEntry = null
        providerCache = null
        providerCacheAt = 0L
        providerFailureAt = 0L
        val json = gson.toJson(config)
        for (f in configFiles) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(json)
                f.setReadable(true, false)
                f.setWritable(true, false)
                Log.i(TAG, "[Config] Saved configuration to ${f.absolutePath}")
            } catch (t: Throwable) {
                Log.e(TAG, "[Config] Error saving to ${f.absolutePath}: ${t.message}")
            }
        }
        try {
            appContext()?.let { ctx ->
                File(ctx.filesDir, "config.json").writeText(json)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[Config] Nelze zapsat privátní kopii configu: ${t.message}")
        }
    }

    fun isModelInstalled(modelId: String): Boolean = getModelDirectory(modelId) != null

    fun getModelDirectory(modelId: String): File? {
        val searchDirs = listOf(
            File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/whisper-$modelId"),
            File("/storage/emulated/0/GalaxyAI/models/whisper-$modelId"),
            File("/sdcard/GalaxyAI/models/whisper-$modelId")
        )
        for (dir in searchDirs) {
            try {
                if (dir.exists()) {
                    val enc = File(dir, "$modelId-encoder.int8.onnx")
                    val dec = File(dir, "$modelId-decoder.int8.onnx")
                    val tok = File(dir, "$modelId-tokens.txt")
                    if (enc.exists() && enc.length() > 500000 && dec.exists() && dec.length() > 500000 && tok.exists()) {
                        return dir
                    }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    fun isDiarizationInstalled(): Boolean {
        val dirs = listOf(
            File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI/models/speaker"),
            File("/storage/emulated/0/GalaxyAI/models/speaker"),
            File("/sdcard/GalaxyAI/models/speaker")
        )
        for (dir in dirs) {
            try {
                val seg = File(dir, "segmentation.onnx")
                val emb = File(dir, "embedding.onnx")
                if (seg.exists() && seg.length() > 100_000 && emb.exists() && emb.length() > 100_000) {
                    return true
                }
            } catch (_: Throwable) {}
        }
        return false
    }

    fun getLanguageByLocale(localeTag: String): SupportedLanguage? {
        return ALL_SUPPORTED_LANGUAGES.find {
            it.localeTag.equals(localeTag, ignoreCase = true) ||
            it.whisperCode.equals(localeTag, ignoreCase = true)
        }
    }

    /**
     * Jazyk systémového Locale (např. "cs-CZ"). Slouží k rozhodnutí, zda VoiceNote
     * nepožaduje oficiální jazyk Samsungu jen jako náhradní řešení za nepodporovaný
     * jazyk systému.
     */
    fun systemLocaleTag(): String {
        val locale = try {
            android.content.res.Resources.getSystem().configuration.locales.get(0)
        } catch (_: Throwable) {
            java.util.Locale.getDefault()
        }
        return locale.toLanguageTag().replace("_", "-")
    }

    /** Normalizuje "en_US" i "en-US" na "en-US". */
    fun normalizeLocaleTag(tag: String?): String {
        if (tag.isNullOrBlank()) return ""
        val parts = tag.replace("_", "-").split("-")
        if (parts.isEmpty()) return ""
        val lang = parts[0].lowercase()
        if (parts.size == 1) return lang
        val region = parts[1].uppercase()
        return "$lang-$region"
    }

    data class ActiveSession(
        val filePath: String = "",
        val isVoicemail: Boolean = false,
        val isCall: Boolean = false,
        val timestamp: Long = 0L
    )

    fun isVoicemailPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val lower = path.lowercase(java.util.Locale.ROOT)
        return lower.contains("/voicemail/") ||
               lower.contains("hlasová schránka") ||
               lower.contains("hlasova schranka") ||
               lower.contains("voicemail")
    }

    fun isCallPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val lower = path.lowercase(java.util.Locale.ROOT)
        return lower.contains("/call/") ||
               lower.contains("hovor ") ||
               lower.contains("call ")
    }

    fun recordActiveSession(filePath: String) {
        try {
            val isVoicemail = isVoicemailPath(filePath)
            val isCall = isCallPath(filePath)
            val json = org.json.JSONObject().apply {
                put("filePath", filePath)
                put("isVoicemail", isVoicemail)
                put("isCall", isCall)
                put("timestamp", System.currentTimeMillis())
            }
            val candidates = listOf(
                File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI/active_session.json"),
                File("/storage/emulated/0/GalaxyAI/active_session.json"),
                File("/data/local/tmp/galaxyai_active_session.json")
            )
            for (f in candidates) {
                try {
                    f.parentFile?.mkdirs()
                    f.writeText(json.toString())
                    Log.i(TAG, "[Config] Aktivní relace zaznamenána: $filePath (isVoicemail=$isVoicemail, isCall=$isCall) do ${f.absolutePath}")
                    break
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[Config] Nelze zaznamenat aktivní relaci: ${t.message}")
        }
    }

    fun getActiveSession(): ActiveSession? {
        val candidates = listOf(
            File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI/active_session.json"),
            File("/storage/emulated/0/GalaxyAI/active_session.json"),
            File("/data/local/tmp/galaxyai_active_session.json")
        )
        for (f in candidates) {
            try {
                if (f.exists() && f.canRead()) {
                    val text = f.readText()
                    val json = org.json.JSONObject(text)
                    val ts = json.optLong("timestamp", 0L)
                    if (System.currentTimeMillis() - ts < 60000L) {
                        return ActiveSession(
                            filePath = json.optString("filePath", ""),
                            isVoicemail = json.optBoolean("isVoicemail", false),
                            isCall = json.optBoolean("isCall", false),
                            timestamp = ts
                        )
                    }
                }
            } catch (_: Throwable) {}
        }
        return null
    }
}
