# Galaxy AI Offline STT – Project Notes & Changelog

This document summarizes the architecture, goals, resolved issues, rules, and development history for integrating offline Speech-to-Text (ASR/STT) into Samsung Voice Recorder, Samsung Notes (Note Assist), and Samsung Intelligence Service on Samsung Galaxy devices (One UI 6.1+, Android 14+) using LSPosed, CzechSpeech, and Whisper.

> Version note: minimum target is Android 14 / One UI 6.1, while primary development and verification run on SM-S918B with Android 17 (SDK 37, One UI 90000) — see Section 7.

---

## 1. Project Goal

- Enable full offline speech transcription for recordings, phone calls, voicemails, and Samsung Notes in Czech and additional languages unsupported natively by Galaxy AI (such as Slovak, Ukrainian, and more) directly in the native Samsung UI.
- Support both **[CzechSpeech q4_0](https://huggingface.co/moravianai/CzechSpeech)** (`whisper.cpp` with ARM `i8mm` acceleration) and quantized **OpenAI Whisper** models (`turbo`, `small`, `base`, `tiny`) via `sherpa-onnx`.
- Provide seamless native One UI integration: structured transcripts with word-level timestamps and speaker avatars/bubbles (`DialogInfo` / `SpeechInfo`).

---

## 2. Core Safety & System Restrictions

1. **Zero modification to system partitions:**
   - Never run `mount -o remount,rw`, never modify `/system`, `/vendor`, `/system_ext`, or `/data/system`.
   - All models and configurations live solely in user-accessible storage (`/storage/emulated/0/`).
   - The LSPosed module operates purely in process RAM via Zygote injection.
2. **Preserve official Galaxy AI language packs:**
   - Keep official language pack selection accessible alongside injected languages (e.g. via native Samsung packs option).
3. **Companion App (`MainActivity.kt`):**
   - Clean Material UI in English without emojis.
   - Allows selecting active STT models, downloading/deleting models and optional speaker diarization packs, configuring enabled languages, and restarting target services.
4. **Speaker Diarization:**
   - Stable, accurate transcription with reliable timing takes priority. Uses lightweight acoustic/VAD diarization for recordings > 30 s, single-speaker monologue mode for voicemails and short non-call memos, and falls back cleanly to Speaker 1 when acoustic separation is insufficient.

---

## 3. Solution Architecture

The module `cz.galaxyai.lsposed` targets three Samsung packages:

### A. Samsung Voice Recorder (`com.sec.android.app.voicenote`)
- Hook class: `cz.galaxyai.lsposed.hooks.VoiceNoteHook`
- Purpose:
  - Hooks `AsrLanguageHelper` and related helpers (`RecognizerUtils`, `AiFeature`, `FastConvertController`) to dynamically expose configured languages as downloaded offline packs and enable `DialogInfo` speaker labels in UI.

### B. Samsung Notes / Note Assist (`com.samsung.android.app.notes`)
- Hook class: `cz.galaxyai.lsposed.hooks.SamsungNotesHook`
- Purpose:
  - Hooks `STTLanguageManager`, `ScsUtils.isLangPackAvailable`, `SettingsAutoTranscribePrefFragment` + `AiLanguageHelper.setSelectModeTranslationLanguageActivity`, and recording/convert session tracking (`FastConvertController`, `RecordRecognizer`, `ScsRecognizer`).

### C. Samsung Intelligence Service (`com.samsung.android.intellivoiceservice`)
- Hook class: `cz.galaxyai.lsposed.hooks.IntelliVoiceHook`
- Purpose:
  - `PipeUserChannel` (`prepare(Bundle)` and `write(ParcelFileDescriptor, IRecognitionListener)`):
    - `prepare(Bundle)`: Detects the requested locale (checking `locale`, explicit keys, and `preferredLocale`/`alternative_locale` lists) and session context (`isVoicemail`, `isCall`).
    - `write(...)`: Intercepts raw 16 kHz 16-bit mono PCM streams and routes decoding through the local `SttEngineManager`.
  - Exits early on `:translation` process (`if (lpparam.processName.endsWith(":translation")) return;`) so proprietary NMT code paths are never disturbed.
- STT Engine (`cz.galaxyai.lsposed.stt.SttEngineManager`):
  - Runs `CzechSpeechBridge` (`whisper.cpp`) or `sherpa-onnx` INT8 Whisper ONNX models.
  - Safely extracts and loads native `.so` libraries from the module APK into the host process's executable `cacheDir`.
  - Performs audio normalization (AGC), VAD / windowing, word timestamp mapping, and `DialogInfo` / `SpeechInfo` construction.

---

## 4. Early Technical Challenges & Resolutions

### 1. Timing Scale Fix (`10x` drift)
- **Problem:** Transcript highlighting/timestamps ran 10× too fast.
- **Root Cause:** Samsung Voice Recorder's `AiWordItemHandler.getWords` multiplies every `timing_info` integer by `10` (`mul-long v2, 10`), expecting centisecond (10 ms) units rather than milliseconds.
- **Resolution:** All `timingInfo` entries emitted by `SttEngineManager` are divided by `10` (`ms / 10`).

### 2. "Samsung Intelligence Service keeps stopping" Crash
- **Problem:** Immediately after transcription completed, Android displayed a crash dialog for `com.samsung.android.intellivoiceservice:translation`.
- **Root Cause:** When official NMT translation packs are absent (`libsrcb_langid_lib.so` missing), hooking or returning null from `TranslationServiceBinder` caused an unhandled `NullPointerException` in `:translation`.
- **Resolution:** Return immediately in `IntelliVoiceHook.init()` whenever `processName.endsWith(":translation")` and keep all hooks strictly focused on ASR (`:asr`).

### 3. Model Read Failure (Scoped Storage & `EACCES`)
- **Problem:** `:asr` (`u0_a135`) lacked `MANAGE_EXTERNAL_STORAGE`, resulting in `Permission denied` (`EACCES`) when reading non-media files under `/storage/emulated/0/GalaxyAI/`.
- **Resolution:**
  - Store models directly in the Intelligence Service app media directory (`/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/`), which `:asr` can read natively without special storage permissions.
  - Keep `ModelProvider` (`cz.galaxyai.lsposed.modelprovider`) with strict caller/path validation and URI grants as a controlled fallback.

---

## 5. On-Device File Layout

- Primary shared model directory:
  `/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/`
  - `whisper-czechspeech/ggml-czechspeech-q4_0.bin`
  - `whisper-turbo/turbo-encoder.int8.onnx`, `turbo-decoder.int8.onnx`, `turbo-tokens.txt`
- Shared configuration:
  `/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI/config.json`
- Optional diagnostic audio (only written when `debugLogging = true`):
  `/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI/last_recording.wav`

---

## 6. Project Status Milestones (Initial Release)

- [x] Compiled and verified on Samsung Galaxy S23 Ultra (SM-S918B).
- [x] Companion UI built in clean English without emojis.
- [x] `MANAGE_EXTERNAL_STORAGE` flow working in companion app.
- [x] `Samsung Intelligence Service` crashes eliminated by bypassing `:translation`.
- [x] Models accessible directly via IVS media directory without touching `/system`.
- [x] End-to-end transcription verified in Samsung Voice Recorder and Samsung Notes (Note Assist).

---

## 7. Verification Against Installed App Versions (2026-11-05)

### Environment

| Item | Value |
| --- | --- |
| Device | SM-S918B (Galaxy S23 Ultra) |
| System | Android 17, SDK 37, One UI 90000, build `CP2A.260605.016/S918BXXUAGZIF` |
| `com.sec.android.app.voicenote` | `21.5.91.33` |
| `com.samsung.android.intellivoiceservice` | `1.3.10.25` (`versionCode 131025200`, `priv-app`, `minSdk 33`) |

### Verified Hooked Targets (PASS)

- `com.sec.android.app.voicenote.helper.AsrLanguageHelper` — `getAsrLanguageState(String)`, `getAvailableLocaleList()`, `getDownloadedLocaleList()`, `getLanguageDisplayName(String)`, `getLanguageInfo(String)`
- `AsrLanguageHelper$AsrLanguageState` — enum values `AVAILABLE`, `DOWNLOADED`, `NOT_SUPPORTED`
- `com.sec.android.app.voicenote.common.util.LanguageInfo` — `ondeviceDict`, `speakerLabel`, `languageName`, `locale`, `isSpeakerLabel()`
- `com.sec.android.app.voicenote.common.util.AiFeature` — `FLAG_IS_SCS_SPEAKER_LABEL_FEATURE_AVAILABLE()`
- `com.sec.android.app.voicenote.common.util.RecognizerUtils` — `isEnableSpeaker()`
- `com.samsung.android.intellivoiceservice.asr.service.PipeUserChannel` — `prepare(Bundle)`, `write(ParcelFileDescriptor, IRecognitionListener)`

### Config Caching Notes

- `ModuleConfig.load()` caches parsed JSON keyed by path, `lastModified`, and `length` (`CacheEntry`, `@Volatile`), returning a copy of `enabledLocales` so UI hooks do not repeat disk I/O per render.

---

## 8. E2E Pipeline Verification & Decoding Improvements (2026-11-06)

### Findings & Fixes

1. **Decoding window context (`buildDecodeWindows`):** Decoding tiny VAD fragments (260 ms pauses) produced hallucinated snippets. `buildDecodeWindows()` merges nearby same-speaker speech islands (gap <= 1800 ms, min 2.5 s, max 30 s window) so Whisper retains proper context.
2. **Word timing extraction (`extractTimedWords`):** Maps token timestamps onto decoded words and splits bubbles (`splitIntoSentences()`) on sentence-ending punctuation and pauses >= 700 ms.
3. **Resilient pipe reader (`readAllTolerant`):** Reads PCM in 64 KB chunks and treats `"Remote side is dead"` as clean end-of-stream rather than discarding already buffered audio.
4. **CPU thread scaling (`decoderThreads()`):** Chooses ONNX decoder thread count dynamically from available CPU cores.
5. **Measured CPU speed (Turbo INT8):** ~0.8x–1.2x real-time depending on recording duration and silence density.

### ADB Test Recipe

```bash
D="-s <device_serial>"
adb $D shell "am force-stop com.sec.android.app.voicenote; am force-stop com.samsung.android.app.notes; am force-stop com.samsung.android.intellivoiceservice"
adb $D logcat -c
adb $D shell "monkey -p com.sec.android.app.voicenote -c android.intent.category.LAUNCHER 1"
```

---

## 9. Shared Configuration Storage Resolution (2026-11-06)

- Process `:asr` runs under a separate UID (`u0_a135`) and cannot read files created under `/storage/emulated/0/GalaxyAI/config.json` when Scoped Storage blocks cross-app access (`exists=true`, `canRead()=false`).
- Reading configuration primarily from `/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI/config.json` allows both the companion app (with `MANAGE_EXTERNAL_STORAGE`) and `:asr` (its own media directory) to share settings reliably.

---

## 10. Speaker Diarization & Multi-Speaker Capping (2026-11-07)

### Speaker Diarization Capping (`limitSpeakers`, `MAX_SPEAKERS = 4`)

- Unconstrained neural clustering on noisy recordings created dozens of spurious speakers (up to 41 clusters on a 6-minute recording, fragmenting decoding into 111 windows).
- `SttEngineManager.limitSpeakers()` keeps the top 4 speakers by total speech duration, merges adjacent segments of the same speaker, and renumbers IDs sequentially (`0..N-1`).
- For recordings longer than 30 s, CPU-efficient energy VAD + acoustic spectral clustering (~15–30 ms total runtime) avoids prolonged CPU heat and lag.

### `AsrResultData` Hook Timing

- `AsrResultData` is only constructed on fresh transcriptions; previously saved transcripts load cached `SpeechInfo` entries directly from Samsung's local database.

---

## 11. Crash Elimination, Model Sizes, Timing & Speaker Diarization Fixes (2026-11-09)

1. **Removed `ManagedSpeakerDiarizerImpl` invocation:** Prevented fatal `UnsatisfiedLinkError` / `SIGSEGV` crashes inside `:asr` when native Samsung diarization libraries are missing.
2. **Multi-model support:** Added Whisper `turbo` (800M, ~1030 MB), `small` (244M, ~370 MB), `base` (74M, ~150 MB), and `tiny` (39M, ~75 MB) INT8 ONNX models synced to the IVS media directory.
3. **Word timing & speaker alignment:** `extractTimedWords()` maps timestamps to exact whitespace-split words matching Samsung's `timing_info`, and `createDialogInfo()` applies +/-50 ms boundary padding to guarantee every word falls inside its bubble.
4. **Optional Speaker Diarization Pack:** Added downloadable Pyannote 3.0 + ERes2Net models (~45 MB) for recordings under 30 s.

---

## 12. Crash & False Error Message Fixes (2026-11-10)

1. **Full elimination of `:translation` crashes:** Removed `TranslationServiceBinder` hooks entirely and added early return in `IntelliVoiceHook.init()` for `:translation`.
2. **Eliminated misleading "Check model download" message on silent clips:** `buildFallbackResult` gained `isModelMissing` flag so silent or noise-only recordings return `[Nebylo rozpoznáno žádné mluvené slovo]` instead of falsely claiming missing model files.

---

## 13. Real-Device English & Czech Verification (2026-11-10)

- **English (`English.m4a`, 122.5 s):** Transcribed offline using Whisper Turbo (`en`), 55 sentences, 377 words, 2 speakers cleanly rendered with avatars and timestamps.
- **Czech (`Hlas 261107_085236`, 16.4 s):** Transcribed in 13.2 s (~0.80x RTF) with multi-speaker bubbles rendered cleanly in One UI.

---

## 14. App Renaming & Multilingual UI Updates (2026-11-10)

- Renamed module to **Galaxy AI Offline STT** (`app_name`) with updated multilingual descriptions in `values/strings.xml` and `values-cs/strings.xml`.
- Updated `MainActivity.kt` copy to be language-neutral, strictly English, and emoji-free.

---

## 15. In-App Model Deletion (2026-11-10)

- Added per-row `Delete` buttons (visible for installed models), a `Delete Selected Model` button, confirmation dialogs, and `ModelDownloader.deleteModel()` cleanup across storage directories.

---

## 16. Release Signing & Initial GitHub Release v1.0.0 (2026-11-10)

- Signed locally with the release keystore (`keystore.properties` and key credentials are excluded from Git via `.gitignore`).
- Published public repository [FoxxoOwO/galaxyai-offline-stt](https://github.com/FoxxoOwO/galaxyai-offline-stt) and release **v1.0.0** ([Release v1.0.0](https://github.com/FoxxoOwO/galaxyai-offline-stt/releases/tag/v1.0.0)).

---

## 17. Live Voicemail, CzechSpeech, Samsung Notes & Audit Hardening (v1.1.0, 2026-11-27)

- **Live Voicemail & Call Recording support:** Single-speaker monologue windowing (`buildMonologueWindows`) prevents hallucination/fragmentation on voicemail and short memos (`isVoicemail`, `isCall`).
- **Moravian AI CzechSpeech q4_0:** Added `CzechSpeechBridge` (`ai.moravian:czechspeech-android`) with CPU feature detection (`i8mm` / `dotprod` / `fp16`), ARM `i8mm` acceleration for Czech, SHA-256 download verification, and automatic Whisper ONNX fallback for non-Czech languages.
- **Samsung Notes (Note Assist) (`com.samsung.android.app.notes`):** Added `SamsungNotesHook` for offline voice-note transcription, `STTLanguageManager` injection, Auto-transcribe language picker integration, and `PipeUserChannel.prepare` locale handling (`preferredLocale`, `alternative_locale`, `alternativeLocale`).
- **Security & Stability Audit Fixes (`v1.1.0`, `versionCode = 2`):**
  - Hardened `ModelProvider` with relative-path allowlisting, canonical path checks, read-only enforcement, and caller package/UID checks.
  - Removed untrusted `/storage` `.so` loader fallback and world-readable/writable chmod calls.
  - Disabled `android:allowBackup` and gated diagnostic WAV dumps and transcript logcat text behind `debugLogging` (`false` by default).
  - Serialized native STT access via `engineLock`, pinned exact HuggingFace commit revisions and SHA-256 hashes for all ONNX and diarization models, fixed empty-locale fallback (`resolveTargetLocale`), switched to single-copy model storage, added a 90-minute PCM safety limit, chunked Sherpa-ONNX fallback, and added 13 JVM unit tests.
  - Published [Release v1.1.0](https://github.com/FoxxoOwO/galaxyai-offline-stt/releases/tag/v1.1.0) with signed `GalaxyAI-Offline-STT-v1.1.0.apk`.
