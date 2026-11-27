package cz.galaxyai.lsposed.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelProviderSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun isAllowedRelativePath_acceptsWhitelistedPaths() {
        assertTrue(ModelProvider.isAllowedRelativePath("config"))
        assertTrue(ModelProvider.isAllowedRelativePath("config.json"))
        assertTrue(ModelProvider.isAllowedRelativePath("whisper-turbo/turbo-encoder.int8.onnx"))
        assertTrue(ModelProvider.isAllowedRelativePath("whisper-turbo/turbo-decoder.int8.onnx"))
        assertTrue(ModelProvider.isAllowedRelativePath("whisper-turbo/turbo-tokens.txt"))
        assertTrue(ModelProvider.isAllowedRelativePath("whisper-czechspeech/ggml-czechspeech-q4_0.bin"))
        assertTrue(ModelProvider.isAllowedRelativePath("speaker/segmentation.onnx"))
        assertTrue(ModelProvider.isAllowedRelativePath("speaker/embedding.onnx"))
    }

    @Test
    fun isAllowedRelativePath_rejectsTraversalAndUnknownFiles() {
        assertFalse(ModelProvider.isAllowedRelativePath(null))
        assertFalse(ModelProvider.isAllowedRelativePath(""))
        assertFalse(ModelProvider.isAllowedRelativePath("   "))
        assertFalse(ModelProvider.isAllowedRelativePath("../config.json"))
        assertFalse(ModelProvider.isAllowedRelativePath("whisper-turbo/../../DCIM/a.jpg"))
        assertFalse(ModelProvider.isAllowedRelativePath("/etc/passwd"))
        assertFalse(ModelProvider.isAllowedRelativePath("whisper-turbo\\turbo-tokens.txt"))
        assertFalse(ModelProvider.isAllowedRelativePath("whisper-unknown/foo.onnx"))
        assertFalse(ModelProvider.isAllowedRelativePath("whisper-turbo/evil.so"))
        assertFalse(ModelProvider.isAllowedRelativePath("whisper-turbo/turbo-tokens.txt\u0000"))
        assertFalse(ModelProvider.isAllowedRelativePath(" config"))
    }

    @Test
    fun resolveSafeFile_preventsEscapingBaseDirEvenIfTargetExists() {
        val outsideFile = tempFolder.newFile("outside.txt").apply { writeText("secret") }
        assertTrue(outsideFile.exists())

        val modelsDir = tempFolder.newFolder("models")
        val turboDir = File(modelsDir, "whisper-turbo").apply { mkdirs() }
        val validTokenFile = File(turboDir, "turbo-tokens.txt").apply { writeText("tok") }

        val resolvedValid = ModelProvider.resolveSafeFile(modelsDir, "whisper-turbo/turbo-tokens.txt")
        assertNotNull(resolvedValid)
        assertEquals(validTokenFile.canonicalPath, resolvedValid!!.canonicalPath)

        assertNull(ModelProvider.resolveSafeFile(modelsDir, "../outside.txt"))
        assertNull(ModelProvider.resolveSafeFile(modelsDir, "whisper-turbo/../../outside.txt"))
        assertNull(ModelProvider.resolveSafeFile(modelsDir, "whisper-turbo/turbo-encoder.int8.onnx"))
    }

    @Test
    fun isAllowedCallerPackage_permitsOnlyTrustedPackages() {
        assertTrue(ModelProvider.isAllowedCallerPackage("com.samsung.android.intellivoiceservice"))
        assertTrue(ModelProvider.isAllowedCallerPackage("com.sec.android.app.voicenote"))
        assertTrue(ModelProvider.isAllowedCallerPackage("com.samsung.android.app.notes"))
        assertTrue(ModelProvider.isAllowedCallerPackage("cz.galaxyai.lsposed"))

        assertFalse(ModelProvider.isAllowedCallerPackage("com.evil.app"))
        assertFalse(ModelProvider.isAllowedCallerPackage(null))
    }
}
