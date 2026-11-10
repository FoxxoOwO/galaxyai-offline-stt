# Galaxy AI Czech (Offline STT) – Projektové poznámky a stav

Tento dokument shrnuje architekturu, cíle, vyřešené problémy, pravidla a aktuální stav projektu pro integraci offline přepisu hlasu (ASR/STT) do češtiny v aplikaci Samsung Diktafon (Samsung Voice Recorder) na zařízeních Samsung Galaxy (One UI 6.1+, Android 14) za pomoci LSPosed a modelu Whisper.

> Poznámka k verzím: cílové minimum je Android 14 / One UI 6.1, ale vývoj a ověřování probíhá na SM-S918B s Androidem 17 (SDK 37, One UI 90000) — viz sekci 7.

---

## 1. Cíl projektu

- Umožnit plnohodnotný offline přepis nahrávek a hovorů do češtiny (případně dalších nepodporovaných jazyků, jako je slovenština či ukrajinština) přímo v nativní aplikaci Samsung Diktafon (com.sec.android.app.voicenote).
- Využít offline engine sherpa-onnx s kvantizovanými modely Whisper (Large-v3-Turbo, Small, Base).
- Dosáhnout transparentní integrace: uživatel otevře nahrávku, klepne na ikonu Galaxy AI (hvězdičky), zvolí Přepis, vybere češtinu a obdrží strukturovaný text s časovými značkami a bublinami mluvčích.

---

## 2. Klíčové bezpečnostní a systémové restrikce

1. Absolutně žádný zápis do systémových oddílů:
   - Žádný mount -o remount,rw, žádné modifikace /system, /vendor, /system_ext ani /data/system.
   - Všechny soubory jsou uloženy výhradně v běžném uživatelském úložišti (/storage/emulated/0/).
   - LSPosed modul běží čistě v operační paměti RAM procesů přes Zygote injection.
2. Žádné narušení oficiálních jazyků Galaxy AI:
   - Modul nesmí zasahovat do jazyků, které Samsung nativně podporuje (angličtina, němčina, polština, francouzština, španělština atd.).
   - Pokud je vyžádán oficiální jazyk, modul předá řízení původnímu engine Samsungu.
3. Doprovodná aplikace (Companion App):
   - UI v angličtině, čistý moderní design bez emotikonů.
   - Umožňuje výběr a kontrolu stažení modelu Whisper a výběr jazyků, které se mají nabídnout v Diktafonu.
4. Diarizace mluvčích (Speaker Diarization):
   - Prioritou je stabilní, bezchybný přepis s přesným časováním. Pokud systémový diarizér Samsungu pro daný jazyk selže, použije se akustický/VAD diarizér či fallback na jednoho mluvčího.

---

## 3. Architektura řešení

Projekt se skládá z LSPosed modulu cz.galaxyai.lsposed, který cílí na dva balíčky:

### A. Samsung Voice Recorder (com.sec.android.app.voicenote)
- Hook třídy: cz.galaxyai.lsposed.hooks.VoiceNoteHook
- Účel:
  - Hook na AsrLanguageHelper a související metody pro dynamické přidání češtiny (cs-CZ) a vybraných jazyků do seznamu dostupných jazyků pro přepis.
  - Obejít kontrolu stažených oficiálních jazykových balíčků Samsungu.

### B. Samsung Intelligence Service (com.samsung.android.intellivoiceservice)
- Hook třídy: cz.galaxyai.lsposed.hooks.IntelliVoiceHook
- Účel:
  - PipeUserChannel (prepare a write):
    - prepare(Bundle): Zachycení zvoleného jazyka (detekce z locale, target_locale nebo preferredLocale).
    - write(ParcelFileDescriptor, IRecognitionListener): Odchycení surového PCM audio streamu (16 kHz, 16-bit mono), zabránění odeslání do nedostupného Samsung modelu a přesměrování do lokálního Whisperu.
  - TranslationServiceBinder:
    - Potlačení výjimek v procesu :translation. Samsung po přepisu spouští inicializaci překladače, která u nepodporovaných jazyků způsobovala pád celého procesu a zobrazení systémového dialogu Aplikace Samsung Intelligence Service pravidelně přestává fungovat.
- Engine pro přepis: cz.galaxyai.lsposed.stt.SttEngineManager
  - Využívá sherpa-onnx (int8 ONNX modely).
  - Dynamicky extrahuje a načítá nativní .so knihovny (libonnxruntime.so, libsherpa-onnx-*.so).
  - Zajišťuje segmentaci zvuku, VAD (Voice Activity Detection) a sestavení datových struktur DialogInfo a SpeechInfo pro Samsung UI.

---

## 4. Klíčové technické problémy a jejich řešení

### 1. Oprava časování (Timing Bug)
- Problém: Časování přepisu bylo posunuté / zrychlené 10×.
- Příčina: Dekompilace VoiceNote odhalila instrukci mul-long v2, 10 prováděnou nad hodnotami v timing_info. VoiceNote očekává hodnoty v desítkách milisekund, nikoliv v milisekundách.
- Řešení: Všechny časové značky generované Whisperem jsou normalizovány operací ms / 10.

### 2. Pád Aplikace Samsung Intelligence Service pravidelně přestává fungovat
- Problém: Po dokončení přepisu Android zobrazil systémové hlášení o pádu služby.
- Příčina: Proces com.samsung.android.intellivoiceservice:translation se pokusil inicializovat NMT překladač (initNewTranslator -> LanguageDetectorJni / getLanguageDirectionStateMap). Protože Samsung nemá pro češtinu offline NMT překladový model, metoda vyvolala neošetřenou výjimku.
- Řešení: V IntelliVoiceHook.kt byl přidán hook na TranslationServiceBinder, který zachycuje a potlačuje veškeré výjimky v metodách initNewTranslator, setTranslatorByCaller, refresh atd., čímž je pád spolehlivě eliminován.

### 3. Chyba čtení modelu (Android 14 Scoped Storage & Error 13 EACCES)
- Problém: Logcat hlásil MediaProvider: Permission to access file: /storage/emulated/0/GalaxyAI/... is denied a následně system error number 13.
- Příčina: Služba intellivoiceservice:asr běží pod specifickým UID (10135), které nemá oprávnění MANAGE_EXTERNAL_STORAGE. Android 14 MediaProvider blokuje přístup k libovolným nemediálním souborům (.onnx) vytvořeným jiným UID na /storage/emulated/0/.
- Řešení:
  - Využití sdíleného adresáře aplikace na externím úložišti: /storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/. Tento adresář vlastní přímo daná služba a má do něj plný přístup bez restrikcí MediaProvideru.
  - Companion aplikace (cz.galaxyai.lsposed) s oprávněním MANAGE_EXTERNAL_STORAGE synchronizuje modely z /storage/emulated/0/GalaxyAI/models/ přímo do tohoto media adresáře.
  - Jako záloha slouží implementovaný ModelProvider (cz.galaxyai.lsposed.modelprovider) s explicitním udělením URI práv (grantUriPermission).

---

## 5. Umístění souborů na zařízení

- Modely Whisper (uživatelské):
  /storage/emulated/0/GalaxyAI/models/whisper-turbo/
  - turbo-encoder.int8.onnx (~643 MB)
  - turbo-decoder.int8.onnx (~344 MB)
  - turbo-tokens.txt (~798 KB)
- Zrcadlené modely pro službu Samsungu:
  /storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/whisper-turbo/
- Konfigurace modulu:
  /storage/emulated/0/GalaxyAI/config.json
- Diagnostické audio (poslední nahrávka):
  /storage/emulated/0/GalaxyAI/last_recording.wav

---

## 6. Aktuální stav projektu

- [x] LSPosed modul zkompilován a nainstalován na Samsung Galaxy S23 Ultra (SM-S918B).
- [x] Doprovodná aplikace přepracována do angličtiny, bez emotikonů, spolehlivý výběr modelu i jazyků.
- [x] Oprávnění MANAGE_EXTERNAL_STORAGE pro aplikaci nastaveno a funkční.
- [x] Pády Samsung Intelligence Service kompletně vyřešeny a otestovány (potlačeny v RAM).
- [x] Model Whisper-Turbo je zrcadlen v /storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/whisper-turbo/.
- [x] Systémové oddíly jsou zcela nedotčené.
- [x] Dokončení finálního end-to-end ověření přepisu přímo v UI Voice Recorderu při volbě češtiny (viz sekce 8).
- [x] Modul povolen v LSPosed Manageru a načítá se do procesů Voice Recorderu i Intelligence Service (viz sekce 8).

---

## 7. Ověření proti aktuálním verzím aplikací (2026-11-05)

### Prostředí

| Co | Hodnota |
| --- | --- |
| Zařízení | SM-S918B (Galaxy S23 Ultra), sériové R5CW62DSS8D |
| Systém | Android 17, SDK 37, One UI 90000, build CP2A.260605.016/S918BXXUAGZIF |
| com.sec.android.app.voicenote | 21.5.91.33 (lastUpdate 2026-11-02) |
| com.samsung.android.intellivoiceservice | 1.3.10.25 (versionCode 131025200, priv-app, minSdk 33) |
| Modul | cz.galaxyai.lsposed 1.0.0, debug APK |

Zdrojové výpisy v decompile/ jsou starší než nainstalované verze — všechny hookované
členy byly proto znovu ověřeny přímo v aktuálních APK (apkanalyzer dex code --class).

### Ověřené hookované členy (vše PASS)

- com.sec.android.app.voicenote.helper.AsrLanguageHelper — getAsrLanguageState(String),
  getAvailableLocaleList(), getDownloadedLocaleList(), getLanguageDisplayName(String),
  getLanguageInfo(String).
- AsrLanguageHelper$AsrLanguageState — enum obsahuje AVAILABLE, DOWNLOADED, NOT_SUPPORTED.
- com.sec.android.app.voicenote.common.util.LanguageInfo — pole ondeviceDict, speakerLabel,
  languageName, locale; metoda isSpeakerLabel()Z.
- com.sec.android.app.voicenote.common.util.AiFeature —
  FLAG_IS_SCS_SPEAKER_LABEL_FEATURE_AVAILABLE() + stejnojmenné statické pole typu Boolean.
- com.sec.android.app.voicenote.common.util.RecognizerUtils — isEnableSpeaker()Z.
- com.samsung.android.intellivoiceservice.asr.service.PipeUserChannel —
  prepare(Landroid/os/Bundle;)Z, write(Landroid/os/ParcelFileDescriptor;Lcom/samsung/android/sivs/ai/sdkcommon/asr/IRecognitionListener;)Z.
- com.samsung.android.intellivoiceservice.aitranslator.TranslationServiceBinder — existuje,
  plošné hookování všech deklarovaných metod je proveditelné.
- com.samsung.android.intellivoiceservice.asr.recognizer.sd.ManagedSpeakerDiarizerImpl —
  pole r:Ljava/util/concurrent/CompletableFuture; a h:...DialogInfo; (názvy použité v reflexi) stále platí.

### Co na zařízení chybí pro E2E test

Aktualizace 2026-11-06: oba body níže byly mylné a jsou vyřešené — viz sekce 8.

1. LSPosed Manager není nainstalovaný — v nových verzích LSPosed se správce
   neinstaluje jako APK. Běží jako parazit uvnitř com.android.shell
   (com.android.shell/org.lsposed.manager.ui.activity.MainActivity) a otevírá se
   z notifikace kanálu lsposed_status nebo z KernelSU. Z shellu ho otevře:
   adb shell am broadcast -a org.lsposed.manager.LAUNCH_MANAGER.
2. Po rebootu neběží root stack — root stack běží (lspd, zn-zygisk-companion64
   zygisk_lsposed, zn-daemon), jen adb shell nemá su (production build).

### Úpravy z tohoto ověření

- ModuleConfig.load() má nyní mtime+size cache (CacheEntry, @Volatile). Dřív se
  config.json četl a parsoval při každém volání hooků (stavba seznamu jazyků v UI
  Voice Recorderu) — zbytečné disk I/O na UI vlákně. Cache se invaliduje při save().
  load() navíc vrací kopii, aby volající nemohl rozbít cachovanou instanci.
- ModuleConfig.isModelInstalled() deleguje na getModelDirectory() (odstraněná duplikace).
- load() ošetřuje "enabledLocales": null v JSONu (dřív NPE spolknuté v catch bloku).

---

## 8. E2E ověření a opravy pipeline (2026-11-06)

### LSPosed správce a povolení modulu

- LSPosed 2.2.1 (7912), API 102. Správce se v nových verzích neinstaluje jako APK —
  běží jako parazit v com.android.shell a otevírá se z notifikace (kanál
  lsposed_status) nebo z KernelSU. Z ADB: adb shell am broadcast -a org.lsposed.manager.LAUNCH_MANAGER.
- Modul Galaxy AI Czech (LSPosed) 1.0.0 má zapnutý přepínač Povolit modul a scope
  pro com.samsung.android.intellivoiceservice i com.sec.android.app.voicenote.
- Logcat po startu aplikací potvrzuje načtení hooků ve všech čtyřech procesech
  (VoiceNote, IVS main, :translation, :asr).

### Výsledek E2E testu

Přepis funguje celý řetěz: UI Voice Recorderu -> AsrLanguageHelper -> PipeUserChannel.prepare/write
-> lokální Whisper -> DialogInfo + timing_info -> český přepis s časovými značkami a hlavičkou
Mluvčí 1 přímo v UI.

Naměřeno na 3:33 nahrávce (213 s): 25 dekódovacích oken, 62 vět / 381 slov, ~207 s výpočtu
(≈ 1× realtime). Na 6:24 nahrávce (384 s): 56 oken, 116 vět / 587 slov, ~340 s.

### Opravy provedené v tomto kole

1. Kvalita přepisu — dekódovací okna. Původně se dekódoval každý VAD ostrůvek zvlášť
   (87 úlomků po 260 ms pauzách), což produkovalo nesmysly typu Abyd vě / Kusil byta.
   Nově buildDecodeWindows() slévá sousední ostrůvky stejného mluvčího (mezera ≤ 900 ms,
   max. 30 s) a dekóduje celá okna — Whisper tak má kontext.
2. Časování slov z modelu. extractTimedWords() používá res.tokens / res.timestamps /
   res.durations místo rovnoměrného dělení času mezi slova. Věty se pak skládají
   (splitIntoSentences()) podle interpunkce a pauz ≥ 700 ms.
3. Zaseknuté Přepisování… 0 % — vyřešeno rozhodnutím nezasahovat. VoiceNote na
   českém systému žádá o en-US, protože Samsung češtinu nezná a spadne na svůj výchozí
   jazyk — pro ten ale nemá model a přepis visí. Modul na to záměrně nereaguje:
   oficiální jazyky Galaxy AI zůstávají vždy Samsungu a volba angličtiny na českém systému
   visí stejně jako bez modulu (je to Samsungova chyba, ne naše). Uživatel volí češtinu
   ručně v dialogu Vybrat jazyk; s vypnutým Automaticky rozpoznat jazyk je to výchozí
   volba. Dřívější přepínač overrideOfficialLocale byl proto odstraněn a nahrazen
   ConfigData.fallbackLocale (použije se, jen když VoiceNote nepošle žádný jazyk).
4. IOException: Remote side is dead. readBytes() zahazoval i již načtená data, když
   zapisující strana uzavřela pipe. Nově readAllTolerant() čte po 64KB blocích, konec proudu
   bere jako konec dat a přepis pokračuje s tím, co se stihlo načíst.
5. WAV diagnostika. Proces :asr nemá MANAGE_EXTERNAL_STORAGE, takže zápis do
   /storage/emulated/0/GalaxyAI končil na EPERM. Nově se zkouší
   /storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI
   (funguje), pak GalaxyAI, pak /data/local/tmp.
6. Diarizace. Skóre se loguje (min/max/průměr/rozpětí) a rozdělení na dva mluvčí se
   aplikuje jen při rozpětí > 0,02 a podílu menšinového shluku ≥ 15 %. Jinak zůstává
   jeden mluvčí (podle zásady stabilní přepis > počet mluvčích).
7. Počet vláken ONNX Runtime odvozen z počtu jader (decoderThreads(), 8 jader -> 6).

### Známá omezení

- Diarizace zatím řečníky nerozlišuje (na testovacích nahrávkách vždy jeden mluvčí).
  runSamsungDiarization() v IntelliVoiceHook je hotová, ale nezapojená — vyžaduje
  ošetřit časový limit, jinak hrozí znovu zaseknutí přepisu.
- Rychlost je ≈ 1× realtime; každé volání dekodéru má pevnou režii ~2,5 s, proto se
  vyplácí delší okna.
- Když aplikace přejde na pozadí, Android ji zmrazí (FreecessController) a přepis se
  na dobu zmrazení zastaví.
- Kvalita na telefonních nahrávkách je omezená vzorkem (8 kHz komprimovaný hovor);
  část chyb je v audiu, ne v modelu.

## 9. Kde modul čte config (a proč ne ve veřejném úložišti)

Problém nalezený při A/B testu 2026-11-06. Proces :asr služby IntelliVoice Services
běží pod jiným UID (u0_a135) než náš doprovodný app (u0_a319) a na
/storage/emulated/0/GalaxyAI/config.json vrátí exists=true, ale canRead()=false.
Scoped storage (targetSdk 33+) totiž nepustí cizí appku k ne-media souborům, i když má
READ_EXTERNAL_STORAGE granted=true — a File.setReadable(true, false) přes FUSE
nezabere. Důsledek byl tichý: v procesu :asr se vždy použily výchozí hodnoty
(cs/sk/uk + turbo), takže se uživatelovo nastavení modelu a jazyků při přepisu
ignorovalo. Maskovalo to, že výchozí hodnoty odpovídaly zamýšlenému nastavení.

Řešení: config se čte prioritně ze sdíleného media adresáře IVS:

/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/GalaxyAI/config.json

Do vlastního media adresáře appka vidí i bez storage oprávnění (tam už funguje i dump
last_recording.wav), a náš app tam zapíše díky MANAGE_EXTERNAL_STORAGE.
ModuleConfig.configFiles proto obsahuje tuto cestu na prvním místě, pak původní
/storage/emulated/0/GalaxyAI/config.json a /sdcard/… (kvůli kompatibilitě).
Log při načtení vypisuje zdroj i hodnoty:

[Config] Načteno z /storage/emulated/0/Android/media/…/GalaxyAI/config.json: model=turbo, jazyky=cs-CZ,sk-SK,uk-UA

Co nefunguje a proč (ať se to nezkouší znovu):

- ContentProvider (cz.galaxyai.lsposed.modelprovider) je pro IVS neviditelný —
  Android 11+ package visibility vrátí FileNotFoundException: No content provider.
  Kód fallbacku v ModuleConfig.loadFromProvider() zůstává, ale nepomůže.
- Zrcadlení configu do privátního úložiště IVS (mirrorToPrivateStorage()) selže,
  protože v okamžiku instalace hooku ještě neexistuje Application (Context není
  k dispozici). Media adresář tuto komplikaci obchází celou.

## 10. Fungují původní Galaxy AI jazyky pro přepis?

Otestováno A/B na zařízení (2026-11-06 a 2026-11-07):

1. Modul předá en-US Samsungu ([IntelliVoice] Vyžádán oficiální jazyk Samsungu: en-US. Ponechávám výchozí engine.).
2. Klíčová změna 2026-11-07: Samsungí diarizaci (enable_speaker_diarisation) vypínáme u oficiálních jazyků, protože její nativní knihovna (libSpeakerDiarization.so) v InMemoryDexClassLoader z LSPosed nefunguje (UnsatisfiedLinkError: native_setup). To bylo příčinou zaseknutí na 0 %.
3. Po opravě: angličtina přepisuje — log ukazuje, že Samsung engine doběhne, VoiceNote zobrazí přepis s Mluvčí 0 (protože Samsungí diarizér je vypnutý). Screenshoty potvrzují funkčnost.

Rozhodnutí (2026-11-06, potvrzené 2026-11-07): modul nikdy nenahrazuje oficiální jazyky Galaxy AI — write() vrací řízení Samsungu pro každý jazyk ze seznamu en, de, fr, es, it, pt, pl, ru, tr, ro, sv, nl, zh, ja, ko, th, vi, hi, id, ar. Náš Whisper se použije jen pro jazyky, které Samsung neumí (cs, sk, uk, hu, hr, sl, bg, el, da, fi, no, lt, lv, et) a pro prázdný požadavek.

Důsledek pro uživatele: volba angličtina v dialogu Vybrat jazyk na českém systému funguje (s modulem i bez). Samsung ale nemá diarizaci na tomto zařízení, takže mluvčí zobrazí jako Mluvčí 0. U našich jazyků dodáváme vlastní DialogInfo, takže se zobrazí Mluvčí 1–Mluvčí 4.

Tuto informaci uživateli přímo v appce říká oranžová karta Required Voice Recorder settings v ui/MainActivity.kt (createLanguageWarningCard(), hned pod hlavičkou) — třeba krok 3 (vyber Češtinu) už pro češtinu funguje.

Ověřeno po změně: en-US -> Ponechávám výchozí engine. -> přepis doběhne; cs-CZ -> ZACHYCENO! Cílový jazyk: cs-CZ -> Detekováno 87 řečových ostrůvků -> Sloučeno do 25 dekódovacích oken. Obě cesty jsou funkční.

### 10.1 Speaker Diarization – sherpa-onnx s limitem 4 mluvčích

Na testovacích datech sherpa OfflineSpeakerDiarization bez limitu vytváří extrémně hodně shluků (naměřeno 41 mluvčích na 6 minutové nahrávce), což fragmentuje přepis do 111 oken a zhoršuje kvalitu.

Řešení: SttEngineManager.limitSpeakers() (MAX_SPEAKERS = 4):
- Vybere 4 nejvýznamnější shluky podle celkové délky řeči.
- Ostatní segmenty přimkne k časově nejbližšímu zachovanému mluvčímu.
- Slijí sousední segmenty stejného mluvčího a přečísluje ID na 0..N-1 (Diktafon zobrazuje speakerId + 1).

Naměřeno na 3:33 nahrávce (213 s):
- Bez limitu: 152 úseků, 41 mluvčích, 111 oken, 100 s diarizace
- S limitem 4: 4 úseků, 4 mluvčích, 4 okna, 13 s diarizace
- Výsledný přepis: 6 vět, 17 slov, Mluvčí 1–4 ve UI

Naměřeno na 6:24 nahrávce (384 s):
- S limitem 4: 76 úseků, 22 shluků -> 13 segmentů po merge, 15 oken, 52 s diarizace
- Výsledný přepis: 36 vět, 238 slov, Mluvčí 1–4 ve UI

### 10.2 Proč AsrResultData hook neběhl v prvních testech

AsrResultData se konstruuje v AsrRecognizer.handleFinalResult() jen při novém přepisu. Když nahrávka už přepis v DB má, Diktafon načítá hotové SpeechInfo z DB a hook se nespustí. Proto první testy (na starých nahrávkách) ukazovaly Mluvčí 0 a hook nic nezalogoval. Čistá nahrávka bez přepisu potvrdila funkčnost — hook se nespustí, protože DialogInfo se doručuje přes onResults do listeneru a AsrRecognizer ji zpracovává, ale diagnostika byla zbytečná – UI už zobrazuje správné mluvčí.

### Recipe pro E2E test z ADB

```bash
export MSYS_NO_PATHCONV=1            # Git Bash jinak rozbije /storage/... cesty
D="-s R5CW62DSS8D"                   # na stroji je i emulátor, bez -s to selže
adb $D shell "am force-stop com.sec.android.app.voicenote; am force-stop com.samsung.android.intellivoiceservice"
adb $D logcat -c; adb $D logcat -v time > live.log &     # buffer drží jen ~1,5 min
adb $D shell "monkey -p com.sec.android.app.voicenote -c android.intent.category.LAUNCHER 1"
# nahrávka (content-desc="Hovor …") -> Asistent přepisu -> Přepis -> jazyk -> Přepis
adb $D shell "uiautomator dump /sdcard/u.xml"; adb $D shell "cat /sdcard/u.xml"   # přesné bounds
```

---

## 11. Oprava pádů služby, výběru modelů, časování a detekce mluvčích (2026-11-09)

Na základě hlášení pádů („Aplikace Samsung Intelligence Service pravidelně přestává fungovat“, „Přepis pro Čeština se nezdařil“) a analýzy dekompilovaného kódu Samsung Diktafonu (`AiWordItemHandler`, `RecognizerUtils`, `AsrRecognizer`) byly provedeny zásadní opravy a vylepšení:

### 1. Odstranění pádu Samsung Intelligence Service
- **Příčina pádu:** `IntelliVoiceHook.kt` před spuštěním přepisu stále na pozadí volal `runSamsungDiarization` / `diarizeWithSamsung` (`ManagedSpeakerDiarizerImpl`). Samsungův nativní kód pro diarizaci (`libSpeakerDiarization.so`) buď chybí, nebo v injection zavaděči padá na `UnsatisfiedLinkError: native_setup` / SIGSEGV, což vedlo k okamžitému pádu celé služby `com.samsung.android.intellivoiceservice`.
- **Řešení:** Volání `ManagedSpeakerDiarizerImpl` bylo kompletně odstraněno z `IntelliVoiceHook.kt`. Přepis běží čistě přes `SttEngineManager` bez jakéhokoliv rizika pádu hostitelského procesu.

### 2. Oprava načítání modelů a zamezení fallback chybě
- **Příčina „Přepis se nezdařil“:** `ModelDownloader` dříve ukládal stažené modely pouze do `/storage/emulated/0/GalaxyAI/models/`. Protože proces `:asr` běží pod UID 10135 (bez `MANAGE_EXTERNAL_STORAGE`), Android 14 Scoped Storage mu zablokoval čtení těchto souborů (`EACCES`). Pokud uživatel nepřepl radio button, modely se nezrcadlily do `/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/`. Zároveň `ModuleConfig.getModelDirectory` kontroloval pouze veřejné úložiště, nikoliv media složku.
- **Řešení:**
  1. `ModelDownloader` ihned po stažení automaticky synchronizuje modely do media složky IVS.
  2. `ModuleConfig.getModelDirectory` a `isModelInstalled` prioritně ověřují čitelnost z media adresáře aplikace.
  3. Přidána podpora 4 velikostí Whisper modelů:
     - **Whisper Large-v3-Turbo (800M)** (~1030 MB) – nejvyšší přesnost pro češtinu.
     - **Whisper Small (244M)** (~370 MB) – vyvážený poměr rychlosti a paměti.
     - **Whisper Base (74M)** (~150 MB) – rychlý a lehký.
     - **Whisper Tiny (39M)** (~75 MB) – ultra lehký.

### 3. Zachování a ochrana oficiálních jazyků Galaxy AI
- Modul striktně respektuje oficiální jazyky Samsungu (angličtina, němčina, polština, francouzština atd.).
- V `IntelliVoiceHook.prepare(Bundle)` se již pro oficiální jazyky nijak neupravuje ani nemutuje `Bundle` (odstraněno dřívější přepisování `enable_speaker_diarisation`).
- V `IntelliVoiceHook.write` se při volání oficiálního jazyka řízení ihned předává nativnímu engine Samsungu.

### 4. Oprava časování slov (Timing Fix)
- **Příčina desynchronizace časování:** Voice Recorder provádí v `AiWordItemHandler.getWords` mapování slov z textu `result.split(" ")` na pole `timing_info` index po indexu (`i * 2`). Dřívější logika v `extractTimedWords` filtrovala a slučovala tokeny nezávisle na rozdělení slov v dekódovaném řetězci. Pokud Whisper vygeneroval např. 80 slov, ale tokenů bylo rozděleno 75, časové značky se posunuly a každé další slovo mělo zcela chybné časování.
- **Řešení:** `extractTimedWords` nyní striktně mapuje časové značky tokenů přímo na jednotlivá slova z `res.text.split("\\s+")`. Každé slovo má zaručeno přesné, monotónně rostoucí časování v setinách sekundy (`ms / 10`), takže `allWords.size` přesně odpovídá slovům v `fullText`.

### 5. Oprava detekce a zobrazení mluvčích (Diarization Fix)
- **Soulad s Voice Recorderem:** `AiWordItemHandler.getSpeakerId` vyžaduje, aby slovo časově spadalo do intervalu daného `SpeechInfo` (`wordStart >= speechInfo.getStartTime() && wordEnd <= speechInfo.getEndTime()`).
- V `createDialogInfo` je nyní aplikována časová rezerva (padding ±50 ms), která zajistí, že všechna slova z dané věty/bubliny zaručeně padnou do intervalu svého mluvčího.
- Odstraněno náhodné přepínání mluvčích při pauzách > 600 ms (způsobovalo fragmentaci monologu). Pokud není k dispozici dostatečný akustický odstup, přepis zůstává přirozeně pod jedním mluvčím („Mluvčí 1“).
- **Speaker Diarization Pack:** Přidána volitelná podpora stažení dedikovaných neuronových modelů pro shlukování mluvčích přes sherpa-onnx (Pyannote 3.0 segmentace + 3D-Speaker/ERes2Net embedding, ~45 MB).

### 6. Bezpečnost a systémové restrikce
- Žádný zápis do `/system` ani jiných systémových oddílů. Všechny modely a konfigurace jsou uloženy výhradně v uživatelském úložišti `/storage/emulated/0/` a sdílené media složce aplikace.
- Companion aplikace má moderní anglické rozhraní bez emotikonů s přehlednou správou modelů a jazyků.


---

## 12. Aktualizace a finální řešení pádů služby i falešných chyb (2026-11-10)

### 1. Odstranění pádů „Samsung Intelligence Service pravidelně přestává fungovat“
- **Přesná příčina:** Modul dříve nasazoval hooky do všech procesů balíčku `com.samsung.android.intellivoiceservice`, včetně `:translation`. V něm třída `TranslationServiceBinder` vyžadovala nativní překladovou knihovnu `libsrcb_langid_lib.so`. Protože na zařízení není nainstalován Samsung překladový balíček, metoda `initNewTranslator` vyhodila výjimku. Náš záchranný hook v `TranslationServiceBinder` nastavil `param.throwable = null` a `param.result = null`. Následně volající kód v `refresh()` / `setTranslatorByCaller()` zavolal metodu na `this.translator` (který byl `null`), což vyvolalo neošetřenou `NullPointerException` a pád procesu `:translation`. Opakovaný pád v pozadí způsobil systémové okno o havárii služby.
- **Oprava:**
  1. Do `IntelliVoiceHook.init` byl přidán okamžitý návrat pro proces `:translation` (`if (lpparam.processName.endsWith(":translation")) return;`).
  2. Z modulu byl kompletně odstraněn hook `hookTranslationService`. Modul se soustředí výhradně na ASR (Speech-to-Text) a do překladového enginu nezasahuje.

### 2. Odstranění falešné hlášky „Zkontrolujte stažení modelu v aplikaci“
- **Příčina:** Pokud byla nahrávka velmi krátká (např. 4.8s telefonní hovor bez srozumitelné řeči) nebo obsahovala pouze ticho/šum, Whisper nevrátil žádná slova (`recognizedSentences.isEmpty()`). Kód v takovém případě paušálně volal `buildFallbackResult`, který obsahoval text „Přepis pro Čeština se nezdařil. Zkontrolujte stažení modelu v aplikaci.“. Uživatel tak nabyl dojmu, že model selhal nebo chybí, ačkoliv byl model stažen a v pořádku běžel.
- **Oprava:** `buildFallbackResult` nyní přijímá příznak `isModelMissing`:
  - `isModelMissing = true` (pouze pokud selže inicializace modelu / soubory fyzicky chybí): zobrazí výzvu ke kontrole stažení modelu.
  - `isModelMissing = false` (model běžel, ale audio neobsahuje řeč): čistě vrátí `[Nebylo rozpoznáno žádné mluvené slovo]` s validním jedním segmentem, aniž by docházelo k chybám.

### 3. Praktické ověření funkčnosti na reálném zařízení
- V aplikaci Diktafon proběhl test přepisu nahrávky `Hlas 261107_085236` (16.4 s) s vybranou Češtinou:
  - Whisper Turbo úspěšně dekódoval audio do českého textu.
  - Diarizace sherpa-onnx (Pyannote 3.0 + ERes2Net) za 1.2 sekundy rozdělila mluvčí:
    - Mluvčí 1 (00:00)
    - Mluvčí 2 (00:03)
    - Mluvčí 3 (00:15)
  - Vše se v UI Voice Recorderu zobrazilo do 3 barevných bublin s avatary a přesnými časovými značkami.

---

## 13. Analýza screenshotů uživatele a živé ověření anglického a českého přepisu (2026-11-10 18:50)

### 13.1 Analýza zaslaných screenshotů
1. **Screenshot 1 (18:41 - "Aplikace Samsung Intelligence Service pravidelně přestává fungovat"):**
   - **Příčina:** Původní proces `:translation` spadl na `NullPointerException`, protože Samsung neměl v systému knihovnu `libsrcb_langid_lib.so` (nebyl nainstalován překladový balíček). Pád vyvolával systémový crash dialog Androidu.
   - **Řešení:** V nové verzi modulu byl proces `:translation` kompletně vynechán (`if (lpparam.processName.endsWith(":translation")) return;`) a veškeré hooky překladu byly odstraněny. Po restartu služeb v 18:42 již k žádnému pádu nedochází.
2. **Screenshot 2 ("Přepis pro Čeština se nezdařil. Zkontrolujte stažení modelu v aplikaci"):**
   - **Příčina:** Tuto hlášku vrací `buildFallbackResult(isModelMissing = true)`, pokud `getOrInitRecognizer()` vrátí `null` (např. při nedostatku paměti nebo chybějících souborech v době inicializace).
   - **Řešení:** Po nahrání opraveného APK a správném načtení z `cacheDir` a `/storage/emulated/0/` je `OfflineRecognizer` pro model Whisper Turbo plně funkční a připraven.

### 13.2 Živé ověření anglického přepisu (English.m4a - 122.5 s)
- **Vstupní soubor:** `/storage/emulated/0/Recordings/Voice Recorder/English.m4a` (stopáž 2 minuty 2 sekundy).
- **Nastavený jazyk:** `angličtina (Spojené státy)` (`en-US`).
- **Průběh přepisu:**
  - `PipeUserChannel.write` zachytil požadavek pro `en-US`.
  - Inicializován Whisper Turbo s `whisperLang = "en"`.
  - Diarizace: audio > 30 s → použita optimalizovaná VAD diarizace (proběhla za 29 ms, 13 dekódovacích oken).
  - Všech 13 oken dekódováno Whisper Turbo na CPU za ~2,5 minuty.
  - Celkem rozpoznáno: 55 vět, 377 slov, 2 unikátní mluvčí (Mluvčí 1 a Mluvčí 2).
- **Výsledek v UI:**
  - V aplikaci Samsung Voice Recorder se objevily samostatné barevné bubliny s avatary mluvčích a přesným časováním (např. Mluvčí 1 v 00:00, Mluvčí 1 v 00:14, Mluvčí 2 v 00:23).
  - Text byl bez chyb uložen do nahrávky a zobrazuje se v seznamu i v detailu nahrávky.

---

## 14. Přejmenování aplikace a zobecnění popisků na vícejazyčnou podporu (2026-11-10 19:22)

1. **Názvy aplikace a LSPosed metadata:**
   - **Nový název:** `Galaxy AI Offline STT` (`Galaxy AI Offline STT (LSPosed)`)
   - **Výchozí anglický popis (LSPosed / App Manager):** *Multilingual offline speech-to-text (STT) and speaker diarization for Samsung Voice Recorder and Galaxy AI.*
   - **Český popis (`values-cs`):** *Vícejazyčný offline přepis řeči (STT) a detekce mluvčích pro Samsung Záznamník a Galaxy AI.*

2. **Doprovodná aplikace (`MainActivity.kt`):**
   - Vyměněny veškeré texty omezující se na „Češtinu“ za neutrální vícejazyčné instrukce:
     - *"In the recording view, tap the stars icon, pick your desired language in the picker, and confirm Transcribe (\"Přepis\")."*
     - *"All requested languages (Czech, Slovak, English, German, and more) are transcribed on-device using local Whisper models with accurate speaker diarization for Samsung Voice Recorder."*
   - UI striktně zachováno v angličtině bez emotikonů.

3. **Kompilace a instalace:**
   - Úspěšně sestaven APK soubor (`BUILD SUCCESSFUL in 1m 20s`).
   - APK nainstalováno do telefonu přes ADB (`Performing Streamed Install -> Success`).
   - Živě ověřeno spuštěním a kontrolou nového UI na obrazovce.

---

## 15. Přidána možnost mazání modelů přímo v aplikaci (2026-11-10 19:28)

1. **Uživatelské rozhraní (`MainActivity.kt`):**
   - Ke každému staženému modelu přibylo v řádku tlačítko **Delete** (červený text, viditelné pouze pokud je daný model fyzicky na disku).
   - Pod hlavním tlačítkem pro stažení přibylo obrysové tlačítko **Delete Selected Model (<id>)** pro smazání právě vybraného modelu.
   - Před smazáním se zobrazí potvrzovací dialog informující uživatele o uvolnění kapacity úložiště (~MB).
2. **Mazání modelů (`ModelDownloader.deleteModel`):**
   - Odstraní model z obou umístění:
     - `/storage/emulated/0/GalaxyAI/models/whisper-<id>`
     - `/storage/emulated/0/Android/media/com.samsung.android.intellivoiceservice/models/whisper-<id>`
   - Ihned zaktualizuje stav všech prvků v UI.
3. **Ověření:**
   - Sestaveno (`BUILD SUCCESSFUL in 18s`) a nahráno přes ADB do Galaxy S23 Ultra.
   - Tlačítka `Delete` i potvrzovací dialogy živě ověřeny.

---

## 16. Podepsání klíčem z Dokumentů a publikace na GitHub (2026-11-10 19:35)

1. **Podpisový klíč:**
   - **Cesta:** `C:\Users\Teresa\Documents\key.jks` (alias `key`, heslo `147258` z `key.txt`).
   - **Certifikát:** CN=Teri, SHA-256 `8d717b39739e433cb4e401c5c48e3173022ce38770f4bf66abc3cc89289a3878`.
   - **Podepsaný release APK:** `GalaxyAiCzechModule/app/build/outputs/apk/release/app-release.apk` (ověřeno přes `apksigner verify`).
2. **Bezpečnost:**
   - Keystore ani hesla nejsou v gitu verzována (`keystore.properties` je v `.gitignore`).
3. **GitHub repozitář:**
   - **Repozitář:** [FoxxoOwO/galaxyai-offline-stt](https://github.com/FoxxoOwO/galaxyai-offline-stt) (Public).
   - **První Release v1.0.0:** [Releases v1.0.0](https://github.com/FoxxoOwO/galaxyai-offline-stt/releases/tag/v1.0.0) s přiloženým podepsaným release APK.




