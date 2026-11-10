package cz.galaxyai.lsposed.hooks

import android.util.Log
import cz.galaxyai.lsposed.MainHook.Companion.TAG
import cz.galaxyai.lsposed.config.ModuleConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object VoiceNoteHook {

    private const val CLS_ASR_LANGUAGE_HELPER =
        "com.sec.android.app.voicenote.helper.AsrLanguageHelper"
    private const val CLS_ASR_LANGUAGE_STATE =
        "com.sec.android.app.voicenote.helper.AsrLanguageHelper\$AsrLanguageState"
    private const val CLS_SCS_OPERATOR =
        "com.sec.android.app.voicenote.helper.ScsOperator"

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookLanguageSelection(lpparam.classLoader)
        hookTranslation(lpparam.classLoader)
        hookAsrResultData(lpparam.classLoader)
    }

    /**
     * Dynamicky zpřístupní všechny v konfiguraci povolené jazyky (např. cs-CZ, sk-SK, pl-PL, uk-UA...)
     * jako plnohodnotné offline sady v UI Diktafonu bez narušení existujících sad Samsungu.
     */
    private fun hookLanguageSelection(classLoader: ClassLoader) {
        try {
            val asrLanguageHelperClass = XposedHelpers.findClass(CLS_ASR_LANGUAGE_HELPER, classLoader)
            val languageInfoClass = XposedHelpers.findClass("com.sec.android.app.voicenote.common.util.LanguageInfo", classLoader)
            val asrLanguageStateClass = XposedHelpers.findClass(CLS_ASR_LANGUAGE_STATE, classLoader)
            val downloadedState = XposedHelpers.getStaticObjectField(asrLanguageStateClass, "DOWNLOADED")

            // 1. Zobrazovaný název pro přidané jazyky
            XposedHelpers.findAndHookMethod(
                asrLanguageHelperClass,
                "getLanguageDisplayName",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val locale = param.args[0] as? String ?: return
                        val lang = ModuleConfig.getLanguageByLocale(locale)
                        if (lang != null) {
                            val config = ModuleConfig.load()
                            if (config.enabledLocales.contains(lang.localeTag)) {
                                param.result = lang.displayName
                            }
                        }
                    }
                }
            )

            // 2. Přidání povolených jazyků do seznamu stažených a dostupných jazyků
            XposedHelpers.findAndHookMethod(
                asrLanguageHelperClass,
                "getDownloadedLocaleList",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val list = (param.result as? List<String>)?.toMutableList() ?: mutableListOf()
                        val config = ModuleConfig.load()
                        for (loc in config.enabledLocales) {
                            if (!list.contains(loc)) {
                                list.add(loc)
                            }
                        }
                        param.result = list
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                asrLanguageHelperClass,
                "getAvailableLocaleList",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val list = (param.result as? List<String>)?.toMutableList() ?: mutableListOf()
                        val config = ModuleConfig.load()
                        for (loc in config.enabledLocales) {
                            if (!list.contains(loc)) {
                                list.add(loc)
                            }
                        }
                        param.result = list
                    }
                }
            )

            // 3. Nastavení stavu pro povolené jazyky na DOWNLOADED
            XposedHelpers.findAndHookMethod(
                asrLanguageHelperClass,
                "getAsrLanguageState",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val locale = param.args[0] as? String ?: return
                        val config = ModuleConfig.load()
                        if (config.enabledLocales.any { it.equals(locale, ignoreCase = true) }) {
                            param.result = downloadedState
                        }
                    }
                }
            )

            // 4. Doplnění objektu LanguageInfo pro přidané jazyky a vynucení speakerLabel = true
            XposedHelpers.findAndHookMethod(
                asrLanguageHelperClass,
                "getLanguageInfo",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val locale = param.args[0] as? String ?: return
                        val config = ModuleConfig.load()
                        val matchedLocale = config.enabledLocales.find { it.equals(locale, ignoreCase = true) }
                        if (matchedLocale != null) {
                            if (param.result == null) {
                                val lang = ModuleConfig.getLanguageByLocale(matchedLocale)
                                val name = lang?.displayName ?: matchedLocale
                                val info = XposedHelpers.newInstance(languageInfoClass, matchedLocale, name)
                                param.result = info
                            }
                            param.result?.let { info ->
                                XposedHelpers.setBooleanField(info, "ondeviceDict", true)
                                XposedHelpers.setBooleanField(info, "speakerLabel", true)
                            }
                        }
                    }
                }
            )

            // 5. Povolení příznaku isSpeakerLabel na instanci LanguageInfo
            XposedHelpers.findAndHookMethod(
                languageInfoClass,
                "isSpeakerLabel",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val locale = XposedHelpers.getObjectField(param.thisObject, "locale") as? String ?: return
                        val config = ModuleConfig.load()
                        if (config.enabledLocales.any { it.equals(locale, ignoreCase = true) }) {
                            param.result = true
                        }
                    }
                }
            )

            // 6. Hook na globální dostupnost speaker diarizace v Samsung Diktafonu
            //
            // `AsrRecognizer` čte naše `DialogInfo` jen když
            // `FLAG_IS_SCS_SPEAKER_LABEL_FEATURE_AVAILABLE() && isEnableSpeaker()` (viz
            // AsrRecognizer.handlePartialResult/handleFinalResult: `if (this.isEnableSpeaker)
            // { speechInfoList.addAll(dialogInfo.getSpeakerInfos()) }`). Proto musí být
            // OBA příznaky true, jinak Diktafon naše mluvčí ignoruje a vždy vypíše „Mluvčí 0“.
            //
            // Samsungí diarizér v IntelliVoice Services tím nezapneme – ten se ovládá
            // bundlem `enable_speaker_diarisation`, který v `prepare()` natvrdo vypínáme.
            try {
                val aiFeatureClass = XposedHelpers.findClass("com.sec.android.app.voicenote.common.util.AiFeature", classLoader)
                XposedHelpers.findAndHookMethod(
                    aiFeatureClass,
                    "FLAG_IS_SCS_SPEAKER_LABEL_FEATURE_AVAILABLE",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "[VoiceNote] FLAG_IS_SCS_SPEAKER_LABEL_FEATURE_AVAILABLE nenalezena: ${t.message}")
            }

            // 7. Hook na RecognizerUtils.isEnableSpeaker a updateEnableSpeakerValue
            try {
                val recognizerUtilsClass = XposedHelpers.findClass("com.sec.android.app.voicenote.common.util.RecognizerUtils", classLoader)
                XposedHelpers.findAndHookMethod(
                    recognizerUtilsClass,
                    "isEnableSpeaker",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    }
                )
                XposedHelpers.findAndHookMethod(
                    recognizerUtilsClass,
                    "updateEnableSpeakerValue",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                XposedHelpers.setBooleanField(param.thisObject, "mIsEnableSpeaker", true)
                            } catch (_: Throwable) {}
                        }
                    }
                )
                XposedHelpers.findAndHookMethod(
                    recognizerUtilsClass,
                    "isLangPackInstalled",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "[VoiceNote] RecognizerUtils hooky selhaly: ${t.message}")
            }

            // 8. Hook na AsrRecognizer.startOnDeviceRecognizer
            try {
                val asrRecognizerClass = XposedHelpers.findClass("com.sec.android.app.voicenote.engine.recognizer.AsrRecognizer", classLoader)
                XposedHelpers.findAndHookMethod(
                    asrRecognizerClass,
                    "startOnDeviceRecognizer",
                    java.io.InputStream::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                XposedHelpers.setBooleanField(param.thisObject, "isEnableSpeaker", true)
                            } catch (_: Throwable) {}
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "[VoiceNote] AsrRecognizer.startOnDeviceRecognizer hook selhal: ${t.message}")
            }

            Log.i(TAG, "[VoiceNote] Úspěšně zahookován AsrLanguageHelper s dynamickou podporou jazyků")
        } catch (t: Throwable) {
            Log.e(TAG, "[VoiceNote] Selhalo zahookování AsrLanguageHelper: ", t)
        }
    }

    /**
     * Hook na offline překlad v Diktafonu
     */
    /**
     * Diagnostika: VoiceNote si z bundlu staví `AsrResultData(text, timeInfo, listSpeechInfos, isFinal)`.
     * Loguje, kolik bublin a mluvčích se do něj dostane – tím zjistíme, jestli UI ignoruje
     * naše `DialogInfo`, nebo jestli ho ani nepřečte.
     */
    private fun hookAsrResultData(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookConstructor(
                "com.sec.android.app.voicenote.data.AsrResultData",
                classLoader,
                String::class.java,
                IntArray::class.java,
                List::class.java,
                java.lang.Boolean.TYPE,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.args[2] as? List<*>
                        val speakers = list?.mapNotNull { item ->
                            item?.let { runCatching { XposedHelpers.callMethod(it, "getSpeaker") as Int }.getOrNull() }
                        }?.distinct()
                        Log.i(TAG, "[VoiceNote] AsrResultData: bublin=${list?.size ?: 0}, mluvčí=$speakers, isFinal=${param.args[3]}")
                    }
                }
            )
            Log.i(TAG, "[VoiceNote] Diagnostický hook na AsrResultData nasazen")
        } catch (t: Throwable) {
            Log.w(TAG, "[VoiceNote] Hook na AsrResultData selhal: ${t.message}")
        }
    }

    private fun hookTranslation(classLoader: ClassLoader) {
        try {
            val scsOperatorClass = XposedHelpers.findClass(CLS_SCS_OPERATOR, classLoader)

            XposedHelpers.findAndHookMethod(
                scsOperatorClass,
                "translate",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sourceText = param.args[0] as? String ?: return
                        val targetLang = param.args[1] as? String ?: "cs"
                        Log.i(TAG, "[VoiceNote] Překlad požádán pro cíl: $targetLang")
                    }
                }
            )

            Log.i(TAG, "[VoiceNote] Úspěšně zahookován ScsOperator.translate")
        } catch (t: Throwable) {
            Log.e(TAG, "[VoiceNote] Selhalo zahookování ScsOperator.translate: ", t)
        }
    }
}
