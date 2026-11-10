package cz.galaxyai.lsposed

import android.util.Log
import cz.galaxyai.lsposed.hooks.IntelliVoiceHook
import cz.galaxyai.lsposed.hooks.VoiceNoteHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        const val TAG = "GalaxyAiCzechHook"
        const val PKG_INTELLIVOICE = "com.samsung.android.intellivoiceservice"
        const val PKG_VOICENOTE = "com.sec.android.app.voicenote"

        @Volatile
        var modulePath: String? = null
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
        Log.i(TAG, "Zygote inicializován, modulePath: $modulePath")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            PKG_INTELLIVOICE -> {
                Log.i(TAG, "Navazuji hook na $PKG_INTELLIVOICE (proces: ${lpparam.processName})")
                IntelliVoiceHook.init(lpparam)
            }
            PKG_VOICENOTE -> {
                Log.i(TAG, "Navazuji hook na $PKG_VOICENOTE (proces: ${lpparam.processName})")
                VoiceNoteHook.init(lpparam)
            }
        }
    }
}
