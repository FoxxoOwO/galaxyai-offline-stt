package cz.galaxyai.lsposed.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleConfigLocaleTest {

    @Test
    fun normalizeLocaleTag_normalizesCaseAndSeparators() {
        assertEquals("cs-CZ", ModuleConfig.normalizeLocaleTag("cs_cz"))
        assertEquals("en-US", ModuleConfig.normalizeLocaleTag("en-us"))
        assertEquals("cs", ModuleConfig.normalizeLocaleTag("cs"))
        assertEquals("sk-SK", ModuleConfig.normalizeLocaleTag(" sk_sk "))
        assertEquals("", ModuleConfig.normalizeLocaleTag(null))
        assertEquals("", ModuleConfig.normalizeLocaleTag("   "))
    }

    @Test
    fun matchEnabledLocale_handlesExactPrimaryAndEmptyInputsWithoutPrefixCollision() {
        val enabled = listOf("cs-CZ", "et-EE")

        assertNull(ModuleConfig.matchEnabledLocale("", enabled))
        assertNull(ModuleConfig.matchEnabledLocale(null, enabled))
        assertNull(ModuleConfig.matchEnabledLocale("und", enabled))
        assertEquals("cs-CZ", ModuleConfig.matchEnabledLocale("cs", enabled))
        assertEquals("cs-CZ", ModuleConfig.matchEnabledLocale("cs_CZ", enabled))
        assertEquals("et-EE", ModuleConfig.matchEnabledLocale("et", enabled))
        assertNull(ModuleConfig.matchEnabledLocale("en-US", enabled))
        assertNull(ModuleConfig.matchEnabledLocale("e", enabled))
    }

    @Test
    fun resolveTargetLocale_followsPriorityOrder() {
        val enabled = listOf("cs-CZ", "sk-SK")

        assertEquals(
            "cs-CZ",
            ModuleConfig.resolveTargetLocale("cs", enabled, fallbackLocale = "sk-SK", systemLocale = "en-US")
        )
        assertEquals(
            "en-US",
            ModuleConfig.resolveTargetLocale("en_us", enabled, fallbackLocale = "sk-SK", systemLocale = "cs-CZ")
        )
        assertEquals(
            "sk-SK",
            ModuleConfig.resolveTargetLocale("", enabled, fallbackLocale = "sk_sk", systemLocale = "en-US")
        )
        assertEquals(
            "fr-FR",
            ModuleConfig.resolveTargetLocale("und", enabled, fallbackLocale = "fr_fr", systemLocale = "cs-CZ")
        )
        assertEquals(
            "sk-SK",
            ModuleConfig.resolveTargetLocale("", enabled, fallbackLocale = null, systemLocale = "sk-SK")
        )
        assertEquals(
            "cs-CZ",
            ModuleConfig.resolveTargetLocale("", enabled, fallbackLocale = null, systemLocale = "en-US")
        )
        assertEquals(
            "cs-CZ",
            ModuleConfig.resolveTargetLocale("", emptyList(), fallbackLocale = null, systemLocale = "en-US")
        )
    }

    @Test
    fun pathDetectors_identifyVoicemailAndCallPaths() {
        assertTrue(ModuleConfig.isVoicemailPath("/storage/emulated/0/Recordings/Voicemail/vm_01.m4a"))
        assertTrue(ModuleConfig.isVoicemailPath("Hlasová schránka 001.m4a"))
        assertFalse(ModuleConfig.isVoicemailPath("/storage/emulated/0/Recordings/Voice 001.m4a"))

        assertTrue(ModuleConfig.isCallPath("/storage/emulated/0/Recordings/Call/call_01.m4a"))
        assertTrue(ModuleConfig.isCallPath("Hovor Jan Novak.m4a"))
        assertFalse(ModuleConfig.isCallPath("Poznamka 001.m4a"))
    }
}
