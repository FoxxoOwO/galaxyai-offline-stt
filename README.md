# Galaxy AI Offline Speech-to-Text & Diarization Module

Also check out [S23 Enhancer](https://github.com/FoxxoOwO/s23-ultimate)

[English](#english) | [Čeština](#čeština)

---

<a name="english"></a>
## English

### 1. Overview

**Galaxy AI Offline STT** is an LSPosed / Zygisk framework module designed for modern Samsung Galaxy devices running One UI 6.1+ (Android 14+). It provides fully offline, on-device Speech-to-Text (STT) transcription and multi-speaker diarization for **Samsung Voice Recorder** (`com.sec.android.app.voicenote`), **Samsung Notes / Note Assist** (`com.samsung.android.app.notes`), and **Samsung Intelligence Service** (`com.samsung.android.intellivoiceservice`).

While Samsung's official Galaxy AI offline transcription is limited to a few selected languages and often hangs on certain regional models without cloud access, this project enables:
- **Czech (Čeština)** and any other Whisper-supported language directly in the native Voice Recorder and Samsung Notes (Note Assist / Auto transcribe) UI.
- Offline transcription via **[CzechSpeech q4_0](https://huggingface.co/moravianai/CzechSpeech)** (452 MiB, `whisper.cpp` with ARM `i8mm` acceleration — state-of-the-art accuracy for Czech) and quantized **OpenAI Whisper** models (`turbo`, `small`, `base`, `tiny`) executed through **Sherpa-ONNX** and **ONNX Runtime Mobile** (with automatic fallback for non-Czech languages).
- **Speaker Diarization** (speaker avatars, colored speech bubbles, and word-level timestamps) rendered directly in the native One UI interface.
- **100% Non-destructive & Systemless**: Absolutely zero files are modified or written to `/system`, `/vendor`, or any read-only partition. All models, configurations, and logs live in standard Android user storage (`/storage/emulated/0/`).

---

### 2. How It Works (Architecture)

The module operates across two distinct process boundaries via LSPosed hooks:

```
+-----------------------------------------------------------------------------------+
|                           Samsung Voice Recorder App                             |
|                        (com.sec.android.app.voicenote)                            |
|                                                                                   |
|  [AsrLanguageHelper Hook] ──> Injects custom languages (cs-CZ, etc.) into UI     |
|                                                                                   |
|  [VoiceNote UI] ──> User selects recording & taps ✨ Asistent přepisu             |
+------------------------------------------+----------------------------------------+
                                           | Audio Stream via Pipe IPC
                                           v
+-----------------------------------------------------------------------------------+
|                       Samsung Intelligence Service (:asr)                         |
|                 (com.samsung.android.intellivoiceservice:asr)                     |
|                                                                                   |
|  [PipeUserChannel.write Hook] ──> Intercepts PCM audio (16kHz 16-bit mono)        |
|                                                                                   |
|  [SttEngineManager]                                                               |
|    ├── Auto-Gain Control (AGC)                                                    |
|    ├── Lightweight VAD + Acoustic Diarization (15-30 ms on CPU)                   |
|    └── Whisper ONNX Engine (INT8 Encoder + Decoder with token timestamps)         |
|                                                                                   |
|  [DialogInfo Builder] ──> Constructs native One UI Dialog & Word structures       |
|                                                                                   |
|  [IRecognitionListener.onResults] ──> Delivers results back to Voice Recorder     |
+-----------------------------------------------------------------------------------+
```

1. **UI Injection (`VoiceNoteHook.kt` & `SamsungNotesHook.kt`):**
   - Hooks `AsrLanguageHelper` in `com.sec.android.app.voicenote` and `STTLanguageManager` / `ScsUtils` / Auto-transcribe language picker in `com.samsung.android.app.notes` (Note Assist).
   - Injects Czech (`cs-CZ`) and custom languages into native language pickers (and Samsung Notes Auto transcribe settings), marking them as downloaded and ready for offline use.

2. **Audio Interception (`IntelliVoiceHook.kt`):**
   - Intercepts `PipeUserChannel.write` in the `com.samsung.android.intellivoiceservice:asr` process.
   - Collects raw 16 kHz 16-bit mono PCM audio data sent from Voice Recorder.
   - Completely bypasses the translation process (`:translation`) to prevent crashes caused by missing Samsung proprietary translation libraries.

3. **Audio Pre-processing & Diarization (`SttEngineManager.kt`):**
   - Applies software **Automatic Gain Control (AGC)** to normalize low-volume microphone recordings.
   - For audio longer than 30 seconds, runs an optimized energy-based **Voice Activity Detection (VAD)** and spectral centroid acoustic clustering that finishes in **15–30 milliseconds** on CPU. This avoids the CPU exhaustion that unquantized neural diarizers (such as full Pyannote 3.0) cause on mobile processors.

4. **Transcription (`CzechSpeech whisper.cpp` / `Sherpa-ONNX`):**
   - When `CzechSpeech q4_0` is selected for Czech (`cs`), transcribes locally via `whisper.cpp` (`ggml` q4_0 with ARM `i8mm` repacking and `min_audio_ctx = 512`), reporting 1.9% WER on parliament speech, 7.7% on Czech FLEURS, and 6.8% in noisy environments. Non-Czech languages automatically fall back to any installed Whisper ONNX model.
   - For multilingual ONNX models (`turbo`, `small`, `base`, `tiny`), transcribes sequentially using CPU threads with INT8 quantized Whisper ONNX models and extracts token-level timestamps.

5. **One UI Result Mapping:**
   - Wraps sentences and words into Samsung's proprietary `DialogInfo` and `Dialog` parcelable hierarchy.
   - Assigns speaker identities (`Mluvčí 1`, `Mluvčí 2`, ...) which Voice Recorder automatically colors and decorates with avatars.
   - Converts word timestamps to the native Samsung centisecond scale (10 ms units) required by `timingInfo`.

---

### 3. Performance & Speed Benchmarks

Benchmarks recorded on a **Samsung Galaxy S23 Ultra** (Snapdragon 8 Gen 2 for Galaxy, 4 decoder threads on CPU):

| Model | Audio Duration | Language | Processing Time | Real-Time Factor (RTF) | Accuracy (WER) |
|---|---|---|---|---|---|
| **Whisper Turbo (INT8)** | 16.4 seconds | Czech (`cs-CZ`) | **13.2 seconds** | ~0.80x | Excellent (natural spoken Czech) |
| **Whisper Turbo (INT8)** | 122.5 seconds | English (`en-US`) | **150.0 seconds** | ~1.22x | Near-perfect (377 words, 2 speakers) |
| **Whisper Tiny (INT8)** | 122.5 seconds | English / Czech | **~25.0 seconds** | ~0.20x | Good for clear audio |
| **VAD Diarization** | 122.5 seconds | Any | **0.029 seconds** | <0.001x | Clean 2-speaker separation |

*Note: Real-Time Factor (RTF) < 1.0 means faster than real-time playback.*

---

### 4. Installation & Setup

#### Prerequisites
- Samsung Galaxy device running One UI 6.1 or 6.1.1 (Android 14+).
- Root access via **Magisk**, **KernelSU**, or **APatch**.
- **Zygisk** enabled in root manager.
- **LSPosed** (Zygisk release) installed and active.

#### Installation Steps
1. **Build & Install the APK:**
   - Build the module using Android Studio or command line:
     ```bash
     ./gradlew assembleDebug
     adb install -r app/build/outputs/apk/debug/app-debug.apk
     ```
2. **Enable in LSPosed:**
   - Open the **LSPosed Manager**.
   - Enable the **Galaxy AI Offline STT** module.
   - Ensure the following applications are checked in the scope:
     - `Samsung Voice Recorder` (`com.sec.android.app.voicenote`)
     - `Samsung Notes` (`com.samsung.android.app.notes`)
     - `Samsung Intelligence Service` (`com.samsung.android.intellivoiceservice`)
3. **Place Model Files:**
   - Open the **Galaxy AI Offline STT** companion app to download **CzechSpeech q4_0 (452 MB)** or any Whisper ONNX model (`turbo`, `small`, `base`, `tiny`), switch between them, or delete unused models to free up storage space.
   - Or manually place the model files in `/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/`:
     ```
     whisper-czechspeech/
       └── ggml-czechspeech-q4_0.bin
     whisper-turbo/
       ├── turbo-encoder.int8.onnx
       ├── turbo-decoder.int8.onnx
       └── turbo-tokens.txt
     ```
4. **Restart Target Services:**
   - Force-stop the target applications to apply the hook:
     ```bash
     adb shell am force-stop com.sec.android.app.voicenote
     adb shell am force-stop com.samsung.android.app.notes
     adb shell am force-stop com.samsung.android.intellivoiceservice
     ```

---

### 5. Troubleshooting & Known Issues

#### 1. "Aplikace Samsung Intelligence Service pravidelně přestává fungovat" (Keeps stopping)
- **Cause:** Samsung's `:translation` process tries to load `libsrcb_langid_lib.so` which is missing if official Samsung language packs are not installed.
- **Solution:** Ensure you are running the latest version of the module. The hook strictly exits early on `:translation` (`if (processName.endsWith(":translation")) return;`), preventing any interference with uninstalled translation engines.

#### 2. "Přepis se nezdařil. Zkontrolujte stažení modelu v aplikaci"
- **Cause:** The Whisper model files could not be found or loaded by `SttEngineManager`.
- **Solution:**
  - Verify that the model files exist in `/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/whisper-<model-id>/` (i.e. `*-encoder.int8.onnx`, `*-decoder.int8.onnx`, and `*-tokens.txt` for Whisper ONNX, or `ggml-czechspeech-q4_0.bin` in `whisper-czechspeech/` for CzechSpeech).
  - Check file permissions to ensure they are readable.

#### 3. Native library execution error (`dlopen failed: noexec`)
- **Cause:** Android kernels mount `/storage/emulated/0/` with the `noexec` flag, which prohibits executing `.so` files directly from shared storage.
- **Solution:** The module automatically copies required native binaries (`libonnxruntime.so`, `libsherpa-onnx-jni.so`, etc.) into the host application's private `cacheDir` before calling `System.load()`.

#### 4. Transcription appears frozen at 0%
- **Cause:** Older versions redirected official Samsung languages (like English `en-US`) back to Samsung's proprietary `SDFileConsumer` engine, which indefinitely hangs on some firmware versions.
- **Solution:** Current module versions route all requested languages through Whisper. Set the desired language in Voice Recorder and Whisper will decode it locally.

### 6. Licenses & Attribution (CzechSpeech)
- **Model weights (`moravianai/CzechSpeech`):** © 2026 Jan Rosecký (Moravian AI), dual-licensed — free for non-commercial use under [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/); commercial license available via `info@moravian.ai`.
- **Android native runtime (`ai.moravian:czechspeech-android`):** Required Notice: Copyright (c) 2026 Jan Rosecký (Moravian AI) (https://moravian.ai/czechspeech/), free for noncommercial use under the PolyForm Noncommercial License 1.0.0.

---
---

<a name="čeština"></a>
## Čeština

### 1. Přehled projektu

**Galaxy AI Offline STT** je modul pro LSPosed / Zygisk určený pro telefony Samsung Galaxy s nadstavbou One UI 6.1+ (Android 14+). Umožňuje plnohodnotný **offline přepis řeči na text (STT)** a **rozpoznávání mluvčích (diarizaci)** přímo v nativních systémových aplikacích **Samsung Záznamník** (`com.sec.android.app.voicenote`), **Samsung Notes / Poznámkový asistent** (`com.samsung.android.app.notes`) a systémové službě **Samsung Intelligence Service** (`com.samsung.android.intellivoiceservice`).

Zatímco oficiální Galaxy AI od Samsungu offline češtinu dosud nenabízí a oficiální jazyky na některých modelech zamrzají na 0 %, tento modul přináší:
- **Plnou podporu češtiny** (a dalších jazyků) přímo v oficiálním dialogu výběru jazyků v Záznamníku i v Poznámkovém asistentu v Samsung Notes (včetně automatického přepisu nahrávek).
- Lokální offline přepis pomocí modelu **[CzechSpeech q4_0](https://huggingface.co/moravianai/CzechSpeech)** (452 MiB, `whisper.cpp` s ARM `i8mm` akcelerací — špičková přesnost pro češtinu) a kvantizovaných modelů **OpenAI Whisper** (`turbo`, `small`, `base`, `tiny`) přes **Sherpa-ONNX** a **ONNX Runtime Mobile** (s automatickým fallbackem pro ostatní jazyky).
- **Detekci mluvčích** (barevné bubliny, ikony/avatary mluvčích a časování jednotlivých slov) integrovanou do originálního grafického rozhraní One UI.
- **100% bezpečné a bezzásahové řešení**: Modul **nijak neupravuje systémové oddíly** (`/system`, `/vendor` atd.). Veškeré modely, konfigurace a data jsou uloženy v běžném uživatelském úložišti (`/storage/emulated/0/`).

---

### 2. Jak to funguje (Architektura)

Modul propojuje dvě aplikace pomocí hooků přes LSPosed:

```
+-----------------------------------------------------------------------------------+
|                           Aplikace Samsung Záznamník                             |
|                        (com.sec.android.app.voicenote)                            |
|                                                                                   |
|  [VoiceNoteHook] ─────────> Přidá češtinu (cs-CZ) do nabídky jazyků v UI          |
|                                                                                   |
|  [Uživatelské rozhraní] ──> Uživatel klepne na ✨ Asistent přepisu -> Přepis      |
+------------------------------------------+----------------------------------------+
                                           | Přenos audia přes systémový Pipe IPC
                                           v
+-----------------------------------------------------------------------------------+
|                       Samsung Intelligence Service (:asr)                         |
|                 (com.samsung.android.intellivoiceservice:asr)                     |
|                                                                                   |
|  [IntelliVoiceHook] ──────> Zachytí PCM audio proud (16kHz 16-bit mono)           |
|                                                                                   |
|  [SttEngineManager]                                                               |
|    ├── Automatické vyrovnání hlasitosti (AGC)                                     |
|    ├── Blesková VAD akustická diarizace (15–30 ms na CPU)                         |
|    └── Whisper ONNX Engine (INT8 Encoder + Decoder s časováním tokenů)            |
|                                                                                   |
|  [Sestavení DialogInfo] ──> Vytvoří nativní Samsung struktury Dialog a Word       |
|                                                                                   |
|  [IRecognitionListener] ──> Předá hotový přepis s mluvčími zpět do Záznamníku     |
+-----------------------------------------------------------------------------------+
```

1. **Injekce do rozhraní (`VoiceNoteHook.kt` a `SamsungNotesHook.kt`):**
   - Zachytává metody třídy `AsrLanguageHelper` v aplikaci Záznamník a `STTLanguageManager` / `ScsUtils` / výběr jazyka pro Automatický přepis v aplikaci Samsung Notes (`com.samsung.android.app.notes`).
   - Do nabídky jazyků přidá češtinu (`cs-CZ`) a další povolené jazyky a označí je jako stažené a připravené k offline použití.

2. **Zachycení audio proudu (`IntelliVoiceHook.kt`):**
   - V procesu `com.samsung.android.intellivoiceservice:asr` odchytává volání `PipeUserChannel.write`.
   - Získá kompletní 16 kHz 16-bit mono PCM audio odeslané ze Záznamníku.
   - Proces překladu (`:translation`) zcela ignoruje, čímž předchází pádům kvůli chybějícím proprietárním překladovým knihovnám Samsungu.

3. **Předzpracování a diarizace mluvčích (`SttEngineManager.kt`):**
   - Aplikuje softwarový zisk (**AGC**), aby byly spolehlivě rozpoznány i tiché nahrávky.
   - Pro nahrávky delší než 30 sekund spouští optimalizovanou **VAD detekci řeči** a shlukování podle spektrálního těžiště, které na procesoru telefonu proběhne za **15 až 30 milisekund**. Tím se eliminuje zamrzání telefonu, které způsobovala těžká neurální diarizace (Pyannote).

4. **Přepis (`CzechSpeech whisper.cpp` / `Sherpa-ONNX`):**
   - Při zvolení `CzechSpeech q4_0` pro češtinu (`cs`) probíhá přepis lokálně přes `whisper.cpp` (`ggml` q4_0 s ARM `i8mm` akcelerací a `min_audio_ctx = 512`), s chybovostí WER pouhých 1,9 % na parlamentní řeči, 7,7 % na FLEURS a 6,8 % v šumu. Ostatní jazyky automaticky použijí nainstalovaný vícejazyčný Whisper ONNX model.
   - Pro modely OpenAI Whisper (`turbo`, `small`, `base`, `tiny`) rozpoznává řeč po segmentech na vláknech procesoru za použití INT8 kvantizovaných ONNX modelů a získává přesná časová razítka pro každé slovo.

5. **Zobrazení v One UI:**
   - Výsledky zabalí do Samsung parcelable tříd `DialogInfo` a `Dialog`.
   - Přiřadí identifikátory mluvčích (`Mluvčí 1`, `Mluvčí 2`), které Záznamník automaticky obarví a opatří avatary.
   - Časování slov převede do setin sekundy (jednotky 10 ms), které vyžaduje Samsung pole `timingInfo`.

---

### 3. Rychlost a naměřený výkon

Testováno na telefonu **Samsung Galaxy S23 Ultra** (Snapdragon 8 Gen 2 for Galaxy, 4 CPU vlákna):

| Použitý model | Délka nahrávky | Zvolený jazyk | Doba zpracování | Faktor reálného času (RTF) | Výsledek a přesnost |
|---|---|---|---|---|---|
| **Whisper Turbo (INT8)** | 16,4 sekund | Čeština (`cs-CZ`) | **13,2 sekund** | ~0,80x | Výborná (přirozená hovorová čeština) |
| **Whisper Turbo (INT8)** | 122,5 sekund | Angličtina (`en-US`) | **150,0 sekund** | ~1,22x | Špičková (377 slov, 2 mluvčí rozděleni) |
| **Whisper Tiny (INT8)** | 122,5 sekund | Angličtina / Čeština | **~25,0 sekund** | ~0,20x | Rychlý přepis pro zřetelnou řeč |
| **VAD Diarizace** | 122,5 sekund | Jakýkoliv | **0,029 sekund** | <0,001x | Okamžité rozdělení na mluvčí bez zátěže |

---

### 4. Instalace a zprovoznění

#### Požadavky
- Zařízení Samsung Galaxy s One UI 6.1 nebo novějším (Android 14+).
- Root (Magisk, KernelSU nebo APatch).
- Zapnutý **Zygisk**.
- Nainstalovaný a aktivní **LSPosed** (varianta pro Zygisk).

#### Postup instalace
1. **Instalace APK balíčku:**
   - Sestavte a nainstalujte modul:
     ```bash
     ./gradlew assembleDebug
     adb install -r app/build/outputs/apk/debug/app-debug.apk
     ```
2. **Aktivace v LSPosed:**
   - Otevřete aplikaci **LSPosed**.
   - Povolte modul **Galaxy AI Offline STT**.
   - V seznamu aplikací zkontrolujte zaškrtnutí:
     - `Samsung Záznamník` (`com.sec.android.app.voicenote`)
     - `Samsung Notes` (`com.samsung.android.app.notes`)
     - `Samsung Intelligence Service` (`com.samsung.android.intellivoiceservice`)
3. **Nahrání modelů:**
   - Použijte doprovodnou aplikaci **Galaxy AI Offline STT**, kde lze stáhnout **CzechSpeech q4_0 (452 MB)** nebo libovolný Whisper ONNX model (`turbo`, `small`, `base`, `tiny`), modely přepínat i mazat pro uvolnění místa v úložišti.
   - Případně nahrajte soubory ručně do složky `/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/`:
     ```
     whisper-czechspeech/
       └── ggml-czechspeech-q4_0.bin
     whisper-turbo/
       ├── turbo-encoder.int8.onnx
       ├── turbo-decoder.int8.onnx
       └── turbo-tokens.txt
     ```
4. **Restart služeb:**
   - Ukončete aplikace, aby se načetl nový kód:
     ```bash
     adb shell am force-stop com.sec.android.app.voicenote
     adb shell am force-stop com.samsung.android.app.notes
     adb shell am force-stop com.samsung.android.intellivoiceservice
     ```

---

### 5. Možné problémy a jejich řešení

#### 1. Hlášení „Aplikace Samsung Intelligence Service pravidelně přestává fungovat“
- **Příčina:** Původní proces překladu (`:translation`) vyžadoval proprietární knihovnu `libsrcb_langid_lib.so`, která bez stažených oficiálních jazyků Samsungu v systému chybí.
- **Řešení:** V aktuální verzi modulu je proces `:translation` zcela ignorován (`if (processName.endsWith(":translation")) return;`), k žádnému pádu ani systémovému dialogu nedochází.

#### 2. Text v záznamu „Přepis se nezdařil. Zkontrolujte stažení modelu v aplikaci“
- **Příčina:** Modulu se nepodařilo načíst soubory modelu Whisper (soubory chybí nebo jsou poškozené).
- **Řešení:** Zkontrolujte, zda v `/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/whisper-<id_modelu>/` skutečně existují všechny soubory modelu (`*-encoder.int8.onnx`, `*-decoder.int8.onnx`, `*-tokens.txt` pro Whisper ONNX, nebo `ggml-czechspeech-q4_0.bin` ve složce `whisper-czechspeech/`).

#### 3. Chyba spouštění knihovny (`dlopen failed: noexec`)
- **Příčina:** Oddíl `/storage/emulated/0/` je jádrem Androidu připojen s příznakem `noexec`, který zakazuje přímé spouštění `.so` knihoven z uživatelského úložiště.
- **Řešení:** Modul automaticky zkopíruje potřebné knihovny (`libonnxruntime.so`, `libsherpa-onnx-jni.so` atd.) do spustitelného `cacheDir` hostitelské aplikace před voláním `System.load()`.

#### 4. Přepis se zasekne na 0 %
- **Příčina:** Starší verze modulu předávaly oficiální jazyky (např. angličtinu) zpět nativnímu enginu Samsungu, který na některých zařízeních trvale zamrzal na `mProgress=0`.
- **Řešení:** Aktuální verze modulu přepisuje všechny vybrané jazyky lokálně pomocí Whisperu.

### 6. Licence a uvedení autorů (CzechSpeech)
- **Váhy modelu (`moravianai/CzechSpeech`):** © 2026 Jan Rosecký (Moravian AI), duální licence — zdarma pro nekomerční použití pod [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/); komerční licence je dostupná přes `info@moravian.ai`.
- **Android runtime (`ai.moravian:czechspeech-android`):** Required Notice: Copyright (c) 2026 Jan Rosecký (Moravian AI) (https://moravian.ai/czechspeech/), zdarma pro nekomerční použití pod PolyForm Noncommercial License 1.0.0.
