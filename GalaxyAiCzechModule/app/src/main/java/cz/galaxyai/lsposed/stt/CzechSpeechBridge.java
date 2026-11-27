package cz.galaxyai.lsposed.stt;

import ai.moravian.czechspeech.internal.CpuThreads;
import ai.moravian.czechspeech.internal.NativeBridge;
import ai.moravian.czechspeech.internal.RepetitionGuard;

/**
 * Compile-time Java bridge to the prebuilt {@code ai.moravian.czechspeech.internal}
 * JNI and utility classes inside {@code czechspeech-android}.
 *
 * <p>Kotlin's {@code internal} visibility compiles to JVM {@code public final} classes
 * with unmangled methods, which cannot be referenced directly from Kotlin source outside
 * the library module, but can be invoked cleanly from Java with standard Java types
 * exposed back to Kotlin.</p>
 */
public final class CzechSpeechBridge {

    private CzechSpeechBridge() {
    }

    public static String systemInfo() {
        return NativeBridge.nativeSystemInfo();
    }

    public static long whisperInit(String modelPath) {
        return NativeBridge.nativeWhisperInit(modelPath);
    }

    public static void whisperFree(long handle) {
        NativeBridge.nativeWhisperFree(handle);
    }

    public static void setMinAudioCtx(long handle, int frames) {
        NativeBridge.nativeWhisperSetMinAudioCtx(handle, frames);
    }

    public static byte[] whisperTranscribe(
            long handle,
            int generation,
            float[] samples,
            int n,
            int threads,
            String languages
    ) {
        return NativeBridge.nativeWhisperTranscribe(handle, generation, samples, n, threads, languages, null);
    }

    public static String cleanRepetitions(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        RepetitionGuard.Result result = RepetitionGuard.INSTANCE.clean(text);
        return result != null ? result.getText() : text;
    }

    public static int defaultThreadCount() {
        return CpuThreads.INSTANCE.defaultThreadCount();
    }
}
