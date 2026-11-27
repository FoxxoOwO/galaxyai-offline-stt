package cz.galaxyai.lsposed.hooks

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import cz.galaxyai.lsposed.MainHook.Companion.TAG
import cz.galaxyai.lsposed.config.ModuleConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale

object SamsungNotesHook {

    private const val CLS_STT_LANGUAGE_MANAGER =
        "com.samsung.android.support.senl.nt.stt.view.language.STTLanguageManager"
    private const val CLS_LANGUAGE_INFO =
        "com.samsung.android.support.senl.nt.stt.view.language.LanguageInfo"
    private const val CLS_SCS_UTILS =
        "com.samsung.android.support.senl.nt.base.winset.app.language.ScsUtils"
    private const val CLS_AI_LANGUAGE_HELPER =
        "com.samsung.android.support.senl.nt.base.winset.app.language.AiLanguageHelper"
    private const val CLS_STT_LANGUAGE_HELPER =
        "com.samsung.android.support.senl.nt.base.winset.app.language.STTLanguageHelper"
    private const val CLS_SHARED_PREFS_COMPAT =
        "com.samsung.android.support.senl.nt.platform.framework.content.SharedPreferencesCompat"
    private const val CLS_FAST_CONVERT_CONTROLLER =
        "com.samsung.android.support.senl.nt.stt.base.audio.FastConvertController"
    private const val CLS_SCS_RECOGNIZER =
        "com.samsung.android.support.senl.nt.stt.base.audio.ScsRecognizer"
    private const val CLS_RECORD_RECOGNIZER =
        "com.samsung.android.support.senl.nt.stt.base.record.RecordRecognizer"
    private const val CLS_SETTINGS_AUTO_TRANSCRIBE =
        "com.samsung.android.support.senl.nt.app.settings.detail.SettingsAutoTranscribePrefFragment"

    private const val SYNTHETIC_PKG_PREFIX = "cz.galaxyai.langpack."

    @Volatile
    private var activeAutoTranscribeFragment: WeakReference<Any>? = null

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookSttLanguageManager(lpparam.classLoader)
        hookScsUtils(lpparam.classLoader)
        hookAutoTranscribeLanguagePicker(lpparam.classLoader)
        hookSessionTracking(lpparam.classLoader)
    }

    private fun syntheticPkg(localeTag: String): String =
        SYNTHETIC_PKG_PREFIX + localeTag.lowercase(Locale.ROOT).replace("-", "_")

    private fun bixbyPkg(localeTag: String): String =
        "com.samsung.android.bixby.ondevice." +
            localeTag.lowercase(Locale.ROOT).replace("-", "").replace("_", "")

    private fun matchEnabledLocale(raw: String?): String? =
        ModuleConfig.matchEnabledLocale(raw, ModuleConfig.load().enabledLocales)

    private fun isOurLangPackage(pkgName: String?): Boolean {
        if (pkgName.isNullOrBlank()) return false
        if (pkgName.startsWith(SYNTHETIC_PKG_PREFIX)) return true
        val enabled = ModuleConfig.load().enabledLocales
        return enabled.any { pkgName.equals(bixbyPkg(it), ignoreCase = true) }
    }

    private fun displayNameFor(localeTag: String): String =
        ModuleConfig.getLanguageByLocale(localeTag)?.displayName ?: localeTag

    private fun createLanguageInfo(languageInfoClass: Class<*>, localeTag: String): Any {
        val name = displayNameFor(localeTag)
        val info = XposedHelpers.newInstance(languageInfoClass, localeTag, name)
        XposedHelpers.callMethod(info, "setOndeviceDict", true)
        XposedHelpers.callMethod(info, "setSpeakerLabel", true)
        XposedHelpers.callMethod(info, "setLanguageDescription", name)
        XposedHelpers.callMethod(info, "setLangPackage", syntheticPkg(localeTag))
        runCatching { XposedHelpers.setBooleanField(info, "isLangpackAvailable", true) }
        return info
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectIntoLanguageManager(manager: Any, languageInfoClass: Class<*>) {
        val enabled = ModuleConfig.load().enabledLocales
        if (enabled.isEmpty()) return

        val infoMap = runCatching {
            XposedHelpers.getObjectField(manager, "mLanguageInfoList") as? MutableMap<String, Any>
        }.getOrNull()
        val downloaded = runCatching {
            XposedHelpers.getObjectField(manager, "mDownloadedLocaleList") as? MutableList<String>
        }.getOrNull()
        val available = runCatching {
            XposedHelpers.getObjectField(manager, "mAvailableLocaleList") as? MutableList<String>
        }.getOrNull()
        val allNames = runCatching {
            XposedHelpers.getObjectField(manager, "mAllLocaleList") as? MutableList<String>
        }.getOrNull()

        for (loc in enabled) {
            if (infoMap != null) {
                val existing = infoMap[loc]
                if (existing == null) {
                    infoMap[loc] = createLanguageInfo(languageInfoClass, loc)
                } else {
                    runCatching { XposedHelpers.callMethod(existing, "setOndeviceDict", true) }
                    runCatching { XposedHelpers.callMethod(existing, "setSpeakerLabel", true) }
                    val pkg = runCatching {
                        XposedHelpers.callMethod(existing, "getLangPackage") as? String
                    }.getOrNull()
                    if (pkg.isNullOrBlank()) {
                        runCatching {
                            XposedHelpers.callMethod(existing, "setLangPackage", syntheticPkg(loc))
                        }
                    }
                    runCatching { XposedHelpers.setBooleanField(existing, "isLangpackAvailable", true) }
                }
            }
            if (downloaded != null && downloaded.none { it.equals(loc, ignoreCase = true) }) {
                downloaded.add(loc)
            }
            if (available != null) {
                available.removeAll { it.equals(loc, ignoreCase = true) }
            }
            val dispName = displayNameFor(loc)
            if (allNames != null && !allNames.contains(dispName)) {
                allNames.add(dispName)
            }
        }
    }

    private fun hookSttLanguageManager(classLoader: ClassLoader) {
        try {
            val sttLangMgrClass = XposedHelpers.findClass(CLS_STT_LANGUAGE_MANAGER, classLoader)
            val languageInfoClass = XposedHelpers.findClass(CLS_LANGUAGE_INFO, classLoader)

            val injectAfter = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    injectIntoLanguageManager(param.thisObject, languageInfoClass)
                }
            }

            XposedHelpers.findAndHookMethod(sttLangMgrClass, "initLanguageData", injectAfter)
            XposedHelpers.findAndHookMethod(sttLangMgrClass, "setLanguageListForDisplay", injectAfter)
            XposedHelpers.findAndHookMethod(sttLangMgrClass, "updateDownloadedLocaleList", injectAfter)

            XposedHelpers.findAndHookMethod(
                sttLangMgrClass,
                "getDownloadedLocaleList",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val list = (param.result as? List<String>)?.toMutableList() ?: mutableListOf()
                        for (loc in ModuleConfig.load().enabledLocales) {
                            if (list.none { it.equals(loc, ignoreCase = true) }) {
                                list.add(loc)
                            }
                        }
                        param.result = list
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                sttLangMgrClass,
                "getAvailableLocaleList",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val list = (param.result as? List<String>)?.toMutableList() ?: return
                        val enabled = ModuleConfig.load().enabledLocales
                        list.removeAll { item -> enabled.any { it.equals(item, ignoreCase = true) } }
                        param.result = list
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                sttLangMgrClass,
                "getLanguageInfo",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rawLocale = param.args[0] as? String ?: return
                        val matched = matchEnabledLocale(rawLocale) ?: return
                        var info = param.result
                        if (info == null) {
                            info = createLanguageInfo(languageInfoClass, matched)
                            param.result = info
                        } else {
                            runCatching { XposedHelpers.callMethod(info, "setOndeviceDict", true) }
                            runCatching { XposedHelpers.callMethod(info, "setSpeakerLabel", true) }
                            val pkg = runCatching {
                                XposedHelpers.callMethod(info, "getLangPackage") as? String
                            }.getOrNull()
                            if (pkg.isNullOrBlank()) {
                                runCatching {
                                    XposedHelpers.callMethod(info, "setLangPackage", syntheticPkg(matched))
                                }
                            }
                            runCatching { XposedHelpers.setBooleanField(info, "isLangpackAvailable", true) }
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                sttLangMgrClass,
                "getLanguageDisplayName",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rawLocale = param.args[0] as? String ?: return
                        val matched = matchEnabledLocale(rawLocale) ?: return
                        param.result = displayNameFor(matched)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                sttLangMgrClass,
                "isLangPackInstalled",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkgName = param.args[0] as? String
                        if (isOurLangPackage(pkgName)) {
                            param.result = true
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                sttLangMgrClass,
                "isLanguageTagInstalled",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val langTag = param.args[0] as? String
                        if (matchEnabledLocale(langTag) != null) {
                            param.result = true
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                sttLangMgrClass,
                "isLanguageSupportSTT",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val locale = param.args[0] as? String
                        if (matchEnabledLocale(locale) != null) {
                            param.result = true
                        }
                    }
                }
            )

            Log.i(TAG, "[Notes] Úspěšně zahookován STTLanguageManager")
        } catch (t: Throwable) {
            Log.e(TAG, "[Notes] Selhalo zahookování STTLanguageManager: ", t)
        }
    }

    private fun hookScsUtils(classLoader: ClassLoader) {
        try {
            val scsUtilsClass = XposedHelpers.findClass(CLS_SCS_UTILS, classLoader)
            XposedHelpers.findAndHookMethod(
                scsUtilsClass,
                "isLangPackAvailable",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val locale = param.args[0] as? String
                        if (matchEnabledLocale(locale) != null) {
                            param.result = true
                        }
                    }
                }
            )
            Log.i(TAG, "[Notes] Úspěšně zahookován ScsUtils.isLangPackAvailable")
        } catch (t: Throwable) {
            Log.w(TAG, "[Notes] Hook na ScsUtils selhal: ${t.message}")
        }
    }

    private fun hookAutoTranscribeLanguagePicker(classLoader: ClassLoader) {
        try {
            val fragmentClass = XposedHelpers.findClass(CLS_SETTINGS_AUTO_TRANSCRIBE, classLoader)
            XposedHelpers.findAndHookMethod(
                fragmentClass,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        activeAutoTranscribeFragment = WeakReference(param.thisObject)
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                fragmentClass,
                "onDestroy",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (activeAutoTranscribeFragment?.get() === param.thisObject) {
                            activeAutoTranscribeFragment = null
                        }
                    }
                }
            )
            for (lifecycleMethod in listOf("onPause", "onStop")) {
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        fragmentClass,
                        lifecycleMethod,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (activeAutoTranscribeFragment?.get() === param.thisObject) {
                                    activeAutoTranscribeFragment = null
                                }
                            }
                        }
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[Notes] Hook na SettingsAutoTranscribePrefFragment selhal: ${t.message}")
        }

        try {
            val aiLanguageHelperClass = XposedHelpers.findClass(CLS_AI_LANGUAGE_HELPER, classLoader)
            val sttLangMgrClass = XposedHelpers.findClass(CLS_STT_LANGUAGE_MANAGER, classLoader)
            val sttLangHelperClass = XposedHelpers.findClass(CLS_STT_LANGUAGE_HELPER, classLoader)
            val sharedPrefsCompatClass = XposedHelpers.findClass(CLS_SHARED_PREFS_COMPAT, classLoader)

            XposedHelpers.findAndHookMethod(
                aiLanguageHelperClass,
                "setSelectModeTranslationLanguageActivity",
                Activity::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val frag = activeAutoTranscribeFragment?.get() ?: return
                        val activity = param.args[0] as? Activity ?: return
                        if (activity.isFinishing || activity.isDestroyed) return
                        val fragActivity = runCatching {
                            XposedHelpers.callMethod(frag, "getActivity") as? Activity
                        }.getOrNull()
                        if (fragActivity != null && fragActivity !== activity) return

                        val sttMgr = XposedHelpers.callStaticMethod(sttLangMgrClass, "getInstance")
                        XposedHelpers.callMethod(sttMgr, "initLanguageData")
                        @Suppress("UNCHECKED_CAST")
                        val downloaded = XposedHelpers.callMethod(sttMgr, "getDownloadedLocaleList") as? List<String>
                            ?: emptyList()

                        val seen = HashSet<String>()
                        val items = downloaded.mapNotNull { loc ->
                            val clean = loc.trim()
                            if (clean.isEmpty() || !seen.add(clean.lowercase(Locale.ROOT))) {
                                null
                            } else {
                                val name = runCatching {
                                    XposedHelpers.callMethod(sttMgr, "getLanguageDisplayName", clean) as? String
                                }.getOrNull()?.takeIf { it.isNotBlank() } ?: displayNameFor(clean)
                                clean to name
                            }
                        }.sortedBy { it.second.lowercase(Locale.getDefault()) }

                        if (items.isEmpty()) return

                        val companion = XposedHelpers.getStaticObjectField(sharedPrefsCompatClass, "Companion")
                        val prefs = XposedHelpers.callMethod(companion, "getInstance", "Settings")
                        val currentLoc = runCatching {
                            XposedHelpers.callMethod(prefs, "getString", "settings_auto_transcription_language", "") as? String
                        }.getOrNull() ?: ""

                        val checkedIndex = items.indexOfFirst { it.first.equals(currentLoc, ignoreCase = true) }
                        val labels = items.map { it.second }.toTypedArray()

                        val titleRes = param.args[1] as? Int ?: 0
                        val title = runCatching {
                            if (titleRes != 0) activity.getString(titleRes) else null
                        }.getOrNull() ?: "Auto transcribe language"

                        AlertDialog.Builder(activity)
                            .setTitle(title)
                            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                                val chosen = items[which].first
                                runCatching {
                                    XposedHelpers.callMethod(prefs, "putString", "settings_auto_transcription_language", chosen)
                                    XposedHelpers.callStaticMethod(sttLangHelperClass, "setDefaultLocaleTag", chosen)
                                }
                                Log.i(TAG, "[Notes] AutoTranscribe jazyk nastaven na: $chosen")
                                dialog.dismiss()
                                activeAutoTranscribeFragment?.get()?.let { frag ->
                                    runCatching {
                                        XposedHelpers.callMethod(frag, "updateAutoTranscriptionLanguageSummary")
                                    }
                                }
                            }
                            .setNeutralButton(
                                if (Locale.getDefault().language in setOf("cs", "sk")) "Sady Samsung" else "Samsung packs"
                            ) { dialog, _ ->
                                dialog.dismiss()
                                runCatching {
                                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()

                        param.result = null
                    }
                }
            )
            Log.i(TAG, "[Notes] Úspěšně zahookován AiLanguageHelper.setSelectModeTranslationLanguageActivity")
        } catch (t: Throwable) {
            Log.w(TAG, "[Notes] Hook na AutoTranscribe language picker selhal: ${t.message}")
        }
    }

    private fun hookSessionTracking(classLoader: ClassLoader) {
        try {
            val fastConvertClass = XposedHelpers.findClass(CLS_FAST_CONVERT_CONTROLLER, classLoader)
            XposedHelpers.findAndHookMethod(
                fastConvertClass,
                "startFastConvert",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val filePath = param.args.getOrNull(1) as? String ?: return
                        Log.i(TAG, "[Notes] FastConvertController.startFastConvert: ${File(filePath).name}")
                        ModuleConfig.recordActiveSession(filePath)
                    }
                }
            )
            Log.i(TAG, "[Notes] Úspěšně zahookován FastConvertController.startFastConvert")
        } catch (t: Throwable) {
            Log.w(TAG, "[Notes] Hook na FastConvertController selhal: ${t.message}")
        }

        try {
            val recordRecognizerClass = XposedHelpers.findClass(CLS_RECORD_RECOGNIZER, classLoader)
            XposedHelpers.findAndHookMethod(
                recordRecognizerClass,
                "startVoiceRecognition",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val locale = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "getLocale")
                        }.getOrNull()
                        Log.i(TAG, "[Notes] RecordRecognizer.startVoiceRecognition locale=$locale")
                        ModuleConfig.recordActiveSession("samsung_notes_live_recording")
                    }
                }
            )
            Log.i(TAG, "[Notes] Úspěšně zahookován RecordRecognizer.startVoiceRecognition")
        } catch (t: Throwable) {
            Log.w(TAG, "[Notes] Hook na RecordRecognizer selhal: ${t.message}")
        }

        try {
            val scsRecognizerClass = XposedHelpers.findClass(CLS_SCS_RECOGNIZER, classLoader)
            XposedHelpers.findAndHookMethod(
                scsRecognizerClass,
                "startRecognizer",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pcmPath = param.args.getOrNull(0) as? String ?: return
                        Log.i(TAG, "[Notes] ScsRecognizer.startRecognizer file=${File(pcmPath).name}")
                    }
                }
            )
            Log.i(TAG, "[Notes] Úspěšně zahookován ScsRecognizer.startRecognizer")
        } catch (t: Throwable) {
            Log.w(TAG, "[Notes] Hook na ScsRecognizer selhal: ${t.message}")
        }
    }
}
