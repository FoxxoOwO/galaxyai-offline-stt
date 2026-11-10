package cz.galaxyai.lsposed.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import cz.galaxyai.lsposed.config.ConfigData
import cz.galaxyai.lsposed.config.ModelInfo
import cz.galaxyai.lsposed.config.ModuleConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var config: ConfigData
    private val languageCheckboxes = mutableMapOf<String, CheckBox>()
    private val modelRadioButtons = mutableMapOf<String, RadioButton>()
    private val modelStatusTexts = mutableMapOf<String, TextView>()
    private val modelDeleteButtons = mutableMapOf<String, MaterialButton>()

    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var btnDownload: MaterialButton
    private lateinit var btnDeleteModel: MaterialButton

    private lateinit var diarizationStatusText: TextView
    private lateinit var btnDiarizationDownload: MaterialButton
    private lateinit var diarizationProgressBar: ProgressBar
    private lateinit var diarizationProgressText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val uri = Uri.parse("content://cz.galaxyai.lsposed.modelprovider")
            grantUriPermission(
                "com.samsung.android.intellivoiceservice",
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        } catch (_: Throwable) {}

        config = ModuleConfig.load()
        val validTags = ModuleConfig.ALL_SUPPORTED_LANGUAGES.map { it.localeTag }.toSet()
        config.enabledLocales.retainAll(validTags)
        ModuleConfig.save(config)
        syncActiveModelToMediaDir()

        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#F6F8FA"))
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 50, 40, 60)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Header
        mainLayout.addView(createHeader())

        // 2. Voice Recorder setup instructions
        mainLayout.addView(createLanguageWarningCard())

        // 3. Speech Recognition Model Selection
        mainLayout.addView(createModelCard())

        // 4. Speaker Diarization Card
        mainLayout.addView(createDiarizationCard())

        // 5. Languages Selection Card
        mainLayout.addView(createLanguagesCard())

        // 6. Action Card (Save & Restart)
        mainLayout.addView(createActionCard())

        scrollView.addView(mainLayout)
        setContentView(scrollView)

        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        refreshModelStatuses()
        refreshDiarizationStatus()
    }

    private fun createHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 30)

            val title = TextView(context).apply {
                text = "Galaxy AI Offline STT"
                textSize = 24f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1F2937"))
            }
            val sub = TextView(context).apply {
                text = "Offline Whisper speech recognition for Samsung Voice Recorder"
                textSize = 14f
                setTextColor(Color.parseColor("#4B5563"))
                setPadding(0, 8, 0, 0)
            }
            addView(title)
            addView(sub)
        }
    }

    private fun createLanguageWarningCard(): View {
        val card = MaterialCardView(this).apply {
            radius = 24f
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#FFFBEB"))
            strokeColor = Color.parseColor("#F59E0B")
            strokeWidth = 2
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 36, 40, 40)
        }

        val title = TextView(this).apply {
            text = "Required Voice Recorder settings"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#92400E"))
        }
        container.addView(title)

        val body = TextView(this).apply {
            text = "Voice Recorder on One UI requires manual selection for added languages:"
            textSize = 13f
            setTextColor(Color.parseColor("#78350F"))
            setPadding(0, 10, 0, 12)
            setLineSpacing(6f, 1f)
        }
        container.addView(body)

        val steps = listOf(
            "1. Open Samsung Voice Recorder -> three-dot menu -> Settings -> Transcription assistant (\"Asistent přepisu\").",
            "2. Turn OFF \"Automatically detect language\" (\"Automaticky rozpoznat jazyk\").",
            "3. In the recording view, tap the stars icon, pick your desired language in the picker, and confirm Transcribe (\"Přepis\")."
        )
        for (step in steps) {
            val tv = TextView(this).apply {
                text = step
                textSize = 13f
                setTextColor(Color.parseColor("#78350F"))
                setPadding(0, 6, 0, 0)
                setLineSpacing(5f, 1f)
            }
            container.addView(tv)
        }

        val footer = TextView(this).apply {
            text = "All requested languages (Czech, Slovak, English, German, and more) are transcribed on-device using local Whisper models with accurate speaker diarization for Samsung Voice Recorder."
            textSize = 12f
            setTextColor(Color.parseColor("#A16207"))
            setPadding(0, 16, 0, 0)
            setLineSpacing(5f, 1f)
        }
        container.addView(footer)

        card.addView(container)
        return card
    }

    private fun createModelCard(): View {
        val card = MaterialCardView(this).apply {
            radius = 24f
            cardElevation = 4f
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 24) }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val cardTitle = TextView(this).apply {
            text = "Speech Recognition Models"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
        }
        val cardDesc = TextView(this).apply {
            text = "Select your active Whisper model or download an alternative size:"
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, 6, 0, 20)
        }
        container.addView(cardTitle)
        container.addView(cardDesc)

        for (model in ModuleConfig.MODELS) {
            val itemRow = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectModel(model.id)
                }
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val rb = RadioButton(this).apply {
                isClickable = false
                isFocusable = false
                isChecked = (model.id == config.selectedModel)
            }
            modelRadioButtons[model.id] = rb
            headerRow.addView(rb)

            val nameTv = TextView(this).apply {
                text = model.name
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1F2937"))
                setPadding(16, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            headerRow.addView(nameTv)

            val btnRowDelete = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Delete"
                textSize = 12f
                setTextColor(Color.parseColor("#DC2626"))
                isAllCaps = false
                visibility = View.GONE
                setPadding(12, 0, 12, 0)
                setOnClickListener {
                    confirmDeleteModel(model)
                }
            }
            modelDeleteButtons[model.id] = btnRowDelete
            headerRow.addView(btnRowDelete)

            itemRow.addView(headerRow)

            val statusTv = TextView(this).apply {
                text = "Checking..."
                textSize = 12f
                setPadding(68, 4, 0, 0)
            }
            modelStatusTexts[model.id] = statusTv
            itemRow.addView(statusTv)

            container.addView(itemRow)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = View.GONE
            setPadding(0, 16, 0, 8)
        }
        container.addView(progressBar)

        progressText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#3B82F6"))
            visibility = View.GONE
            setPadding(0, 0, 0, 16)
        }
        container.addView(progressText)

        btnDownload = MaterialButton(this).apply {
            text = "Download Selected Model"
            cornerRadius = 16
            setOnClickListener {
                startModelDownload()
            }
        }
        container.addView(btnDownload)

        btnDeleteModel = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Delete Selected Model"
            cornerRadius = 16
            setTextColor(Color.parseColor("#DC2626"))
            strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
            strokeWidth = 2
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 0) }
            setOnClickListener {
                val currentModel = ModuleConfig.MODELS.find { it.id == config.selectedModel }
                if (currentModel != null) {
                    confirmDeleteModel(currentModel)
                }
            }
        }
        container.addView(btnDeleteModel)

        card.addView(container)
        return card
    }

    private fun createDiarizationCard(): View {
        val card = MaterialCardView(this).apply {
            radius = 24f
            cardElevation = 4f
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "Speaker Diarization Pack (Optional)"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
        }
        container.addView(title)

        val desc = TextView(this).apply {
            text = "Neural speaker clustering (Pyannote 3.0 + ERes2Net, ~45 MB). Distinguishes multiple speakers (Speaker 1, Speaker 2...). If not installed, clean single-speaker mode is used."
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, 6, 0, 16)
            setLineSpacing(4f, 1f)
        }
        container.addView(desc)

        diarizationStatusText = TextView(this).apply {
            text = "Checking..."
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }
        container.addView(diarizationStatusText)

        diarizationProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = View.GONE
            setPadding(0, 0, 0, 8)
        }
        container.addView(diarizationProgressBar)

        diarizationProgressText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#3B82F6"))
            visibility = View.GONE
            setPadding(0, 0, 0, 16)
        }
        container.addView(diarizationProgressText)

        btnDiarizationDownload = MaterialButton(this).apply {
            text = "Download Diarization Pack (~45 MB)"
            cornerRadius = 16
            setOnClickListener {
                if (ModuleConfig.isDiarizationInstalled()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        ModelDownloader.deleteDiarization()
                        runOnUiThread {
                            refreshDiarizationStatus()
                            Toast.makeText(this@MainActivity, "Diarization pack removed.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    startDiarizationDownload()
                }
            }
        }
        container.addView(btnDiarizationDownload)

        card.addView(container)
        return card
    }

    private fun selectModel(modelId: String) {
        config.selectedModel = modelId
        for ((id, rb) in modelRadioButtons) {
            rb.isChecked = (id == modelId)
        }
        ModuleConfig.save(config)
        updateDownloadButtonState()
        syncActiveModelToMediaDir()
    }

    private fun syncActiveModelToMediaDir() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelId = config.selectedModel
                val srcDir = File("/storage/emulated/0/GalaxyAI/models/whisper-$modelId")
                if (srcDir.exists() && srcDir.isDirectory) {
                    val dstDir = File("/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/whisper-$modelId")
                    dstDir.mkdirs()
                    val files = srcDir.listFiles() ?: return@launch
                    for (file in files) {
                        val dstFile = File(dstDir, file.name)
                        if (!dstFile.exists() || dstFile.length() != file.length()) {
                            file.copyTo(dstFile, overwrite = true)
                            dstFile.setReadable(true, false)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun createLanguagesCard(): View {
        val card = MaterialCardView(this).apply {
            radius = 24f
            cardElevation = 4f
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val cardTitle = TextView(this).apply {
            text = "Languages for Samsung Voice Recorder"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
        }
        val cardDesc = TextView(this).apply {
            text = "Select the languages to enable in the Voice Recorder picker:"
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, 6, 0, 16)
        }
        container.addView(cardTitle)
        container.addView(cardDesc)

        for (lang in ModuleConfig.ALL_SUPPORTED_LANGUAGES) {
            val cb = CheckBox(this).apply {
                text = "${lang.englishName} (${lang.nativeName}) [${lang.localeTag}]"
                textSize = 14f
                setTextColor(Color.parseColor("#374151"))
                isChecked = config.enabledLocales.contains(lang.localeTag)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        if (!config.enabledLocales.contains(lang.localeTag)) {
                            config.enabledLocales.add(lang.localeTag)
                        }
                    } else {
                        config.enabledLocales.remove(lang.localeTag)
                    }
                    ModuleConfig.save(config)
                }
            }
            languageCheckboxes[lang.localeTag] = cb
            container.addView(cb)
        }

        card.addView(container)
        return card
    }

    private fun createActionCard(): View {
        val card = MaterialCardView(this).apply {
            radius = 24f
            cardElevation = 4f
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val btnSave = MaterialButton(this).apply {
            text = "Save & Apply Settings"
            cornerRadius = 16
            setOnClickListener {
                ModuleConfig.save(config)
                syncActiveModelToMediaDir()
                Toast.makeText(context, "Settings saved. Restarting Voice Recorder...", Toast.LENGTH_SHORT).show()
                restartServices()
            }
        }
        container.addView(btnSave)

        val hint = TextView(this).apply {
            text = "Tip: After downloading or switching models, tap Save & Apply to restart Samsung Voice Recorder and IntelliVoice services."
            textSize = 12f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, 16, 0, 0)
        }
        container.addView(hint)

        card.addView(container)
        return card
    }

    private fun refreshModelStatuses() {
        for (model in ModuleConfig.MODELS) {
            val installed = ModuleConfig.isModelInstalled(model.id)
            val tv = modelStatusTexts[model.id]
            if (tv != null) {
                if (installed) {
                    tv.text = "Installed and ready (~${model.sizeMb} MB)"
                    tv.setTextColor(Color.parseColor("#059669"))
                } else {
                    tv.text = "Not downloaded (~${model.sizeMb} MB)"
                    tv.setTextColor(Color.parseColor("#DC2626"))
                }
            }
            val delBtn = modelDeleteButtons[model.id]
            if (delBtn != null) {
                delBtn.visibility = if (installed) View.VISIBLE else View.GONE
            }
        }
        updateDownloadButtonState()
    }

    private fun refreshDiarizationStatus() {
        val installed = ModuleConfig.isDiarizationInstalled()
        if (installed) {
            diarizationStatusText.text = "Installed and active (~45 MB)"
            diarizationStatusText.setTextColor(Color.parseColor("#059669"))
            btnDiarizationDownload.text = "Delete Diarization Pack"
        } else {
            diarizationStatusText.text = "Not downloaded (single speaker fallback active)"
            diarizationStatusText.setTextColor(Color.parseColor("#6B7280"))
            btnDiarizationDownload.text = "Download Diarization Pack (~45 MB)"
        }
    }

    private fun updateDownloadButtonState() {
        val isInstalled = ModuleConfig.isModelInstalled(config.selectedModel)
        if (isInstalled) {
            btnDownload.text = "Model is installed (Re-download)"
            btnDeleteModel.visibility = View.VISIBLE
            btnDeleteModel.text = "Delete Selected Model (${config.selectedModel})"
        } else {
            btnDownload.text = "Download model (${config.selectedModel})"
            btnDeleteModel.visibility = View.GONE
        }
    }

    private fun confirmDeleteModel(model: ModelInfo) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete Model")
            .setMessage("Do you want to delete ${model.name} (~${model.sizeMb} MB)? This will free up storage space.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = ModelDownloader.deleteModel(model.id)
                    runOnUiThread {
                        if (ok) {
                            Toast.makeText(this@MainActivity, "Model ${model.name} deleted.", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Failed to delete model.", Toast.LENGTH_SHORT).show()
                        }
                        refreshModelStatuses()
                        updateDownloadButtonState()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startModelDownload() {
        val model = ModuleConfig.MODELS.find { it.id == config.selectedModel } ?: return

        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        btnDownload.isEnabled = false

        lifecycleScope.launch {
            val success = ModelDownloader.downloadModel(model) { percent, status ->
                runOnUiThread {
                    progressBar.progress = percent
                    progressText.text = status
                }
            }

            runOnUiThread {
                btnDownload.isEnabled = true
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                if (success) {
                    syncActiveModelToMediaDir()
                    Toast.makeText(this@MainActivity, "Model ${model.name} is ready!", Toast.LENGTH_LONG).show()
                    refreshModelStatuses()
                } else {
                    Toast.makeText(this@MainActivity, "Error downloading model.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startDiarizationDownload() {
        diarizationProgressBar.visibility = View.VISIBLE
        diarizationProgressText.visibility = View.VISIBLE
        btnDiarizationDownload.isEnabled = false

        lifecycleScope.launch {
            val success = ModelDownloader.downloadDiarizationModel { percent, status ->
                runOnUiThread {
                    diarizationProgressBar.progress = percent
                    diarizationProgressText.text = status
                }
            }

            runOnUiThread {
                btnDiarizationDownload.isEnabled = true
                diarizationProgressBar.visibility = View.GONE
                diarizationProgressText.visibility = View.GONE
                if (success) {
                    Toast.makeText(this@MainActivity, "Speaker Diarization Pack ready!", Toast.LENGTH_LONG).show()
                    refreshDiarizationStatus()
                } else {
                    Toast.makeText(this@MainActivity, "Error downloading diarization pack.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun restartServices() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.sec.android.app.voicenote && am force-stop com.samsung.android.intellivoiceservice"))
        } catch (_: Exception) {}
    }
}
