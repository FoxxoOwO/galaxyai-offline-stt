package cz.galaxyai.lsposed.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SttSegmentationTest {

    @Test
    fun buildMonologueWindows_coversShortAndLongAudioWithinMaxWindow() {
        val shortSamples = FloatArray(16000 * 10)
        val shortWindows = SttEngineManager.buildMonologueWindows(10000, shortSamples)
        assertEquals(listOf(SegmentInfo(0, 10000, 0)), shortWindows)

        val longSamples = FloatArray(16000 * 65)
        val longWindows = SttEngineManager.buildMonologueWindows(65000, longSamples)
        assertTrue(longWindows.size >= 3)
        assertEquals(0, longWindows.first().startMs)
        assertEquals(65000, longWindows.last().endMs)
        for (i in longWindows.indices) {
            val w = longWindows[i]
            assertTrue(w.endMs > w.startMs)
            assertTrue(w.endMs - w.startMs <= 28000)
            assertEquals(0, w.speakerId)
            if (i > 0) {
                assertEquals(longWindows[i - 1].endMs, w.startMs)
            }
        }
    }

    @Test
    fun buildDecodeWindows_mergesCloseSameSpeakerSplitsOnChangeAndPadsShort() {
        val samples = FloatArray(16000 * 20)
        val input = listOf(
            SegmentInfo(500, 1200, 0),
            SegmentInfo(1800, 3200, 0),   // gap 600ms <= 1800ms -> merges with previous
            SegmentInfo(3500, 4000, 1)    // different speaker -> separate & padded to >= 2500ms
        )

        val windows = SttEngineManager.buildDecodeWindows(input, samples)
        assertEquals(2, windows.size)
        assertEquals(SegmentInfo(500, 3200, 0), windows[0])
        assertEquals(1, windows[1].speakerId)
        assertEquals(2500, windows[1].endMs - windows[1].startMs)
    }

    @Test
    fun limitSpeakers_andRenumberAndMerge_capsToFourAndRenumbersSequentially() {
        val segments = listOf(
            SegmentInfo(0, 1000, 10),
            SegmentInfo(1000, 2000, 10),   // speaker 10 total 2000ms
            SegmentInfo(2000, 5000, 20),   // speaker 20 total 3000ms
            SegmentInfo(5000, 5200, 99),   // rare outlier total 200ms -> remapped to speaker 20
            SegmentInfo(5200, 9200, 30),   // speaker 30 total 4000ms
            SegmentInfo(9200, 14200, 40),  // speaker 40 total 5000ms
            SegmentInfo(14200, 14500, 50)  // rare outlier total 300ms -> remapped to speaker 40
        )

        val result = SttEngineManager.limitSpeakers(segments, maxSpeakers = 4)
        val distinctSpeakers = result.map { it.speakerId }.distinct()
        assertTrue(distinctSpeakers.size <= 4)
        assertEquals(listOf(0, 1, 2, 3), distinctSpeakers)
        // Adjacent segments of same speaker were merged
        assertEquals(4, result.size)
        assertEquals(0, result[0].startMs)
        assertEquals(2000, result[0].endMs)
        assertEquals(2000, result[1].startMs)
        assertEquals(5200, result[1].endMs)
    }

    @Test
    fun normalizeExternalSegments_convertsSamplesSplitsAndFiltersInvalid() {
        val segs = listOf(
            SegmentInfo(0, 640_000, 0),        // looks like samples (> 2 * 40000ms) -> 0..40000 ms -> splits to 30000 + 10000
            SegmentInfo(16_000, 17_000, 1),    // 1000..1062ms -> 62ms < 200ms -> dropped
            SegmentInfo(32_000, 24_000, 1)     // invalid -> dropped
        )

        val normalized = SttEngineManager.normalizeExternalSegments(segs, totalDurationMs = 40_000)
        assertEquals(2, normalized.size)
        assertEquals(SegmentInfo(0, 30_000, 0), normalized[0])
        assertEquals(SegmentInfo(30_000, 40_000, 0), normalized[1])
    }

    @Test
    fun splitIntoSentences_splitsOnPunctuationAndLargeGaps() {
        val words = listOf(
            TimedWord("Ahoj,", 0, 300),
            TimedWord("světe.", 350, 700),
            TimedWord("Jak", 800, 950),
            TimedWord("se", 1000, 1100),
            TimedWord("máš", 1900, 2200), // gap 800ms >= 700ms after "se"
            TimedWord("dnes", 2250, 2500)
        )

        val sentences = SttEngineManager.splitIntoSentences(words)
        assertEquals(3, sentences.size)
        assertEquals(listOf("Ahoj,", "světe."), sentences[0].map { it.text })
        assertEquals(listOf("Jak", "se"), sentences[1].map { it.text })
        assertEquals(listOf("máš", "dnes"), sentences[2].map { it.text })
    }
}
