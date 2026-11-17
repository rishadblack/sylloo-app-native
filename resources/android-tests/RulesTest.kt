package com.rishadblack.plugins.sylloo_app_native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of the jobs (plan §4.6), checked on the computer: `gradlew testDebugUnitTest`. */
class RulesTest {

    @Test
    fun secretNamesValuesAndFiles() {
        assertTrue(SecretRules.isName("phone.data-key"))
        assertFalse(SecretRules.isName("Phone"))
        assertFalse(SecretRules.isName("a/b"))
        assertFalse(SecretRules.isName("a".repeat(65)))
        assertFalse(SecretRules.isValue(""))
        assertFalse(SecretRules.isValue("x".repeat(4097)))
        assertTrue(SecretRules.isValue("x".repeat(4096)))
        assertEquals(68, SecretRules.fileName("phone.data-key").length)
        assertFalse(SecretRules.fileName("phone.data-key").contains("phone"))
    }

    @Test
    fun locationTimeoutsFreshnessAndTheBetterFix() {
        assertEquals(10_000L, LocationRules.timeout(null))
        assertEquals(2_000L, LocationRules.timeout(0))
        assertEquals(60_000L, LocationRules.timeout(600_000))
        assertTrue(LocationRules.isFresh(5_000, 5_500))
        assertFalse(LocationRules.isFresh(1_000, 5_000))
        assertTrue(LocationRules.better(5f, false, 10f, true))
        assertTrue(LocationRules.better(10f, true, 10f, false))
        assertFalse(LocationRules.better(10f, false, 10f, true))
    }

    @Test
    fun photosAreMadeSmallKeepingTheirShape() {
        assertEquals(1280, PhotoSize.maxSide(null))
        assertEquals(320, PhotoSize.maxSide(10))
        assertEquals(2048, PhotoSize.maxSide(9_000))
        assertEquals(95, PhotoSize.quality(100))
        assertEquals(Pair(1024, 768), PhotoSize.fit(4000, 3000, 1024))
        assertEquals(Pair(768, 1024), PhotoSize.fit(3000, 4000, 1024))
        assertEquals(Pair(800, 600), PhotoSize.fit(800, 600, 1024))
    }

    @Test
    fun pushTextsAndAddresses() {
        assertEquals("abc", PushRules.text("  abcdef ", 3))
        assertEquals("https://demo.sylloo.app/member/leave/5", PushRules.address("https://demo.sylloo.app/member/leave/5"))
        assertNull(PushRules.address("http://demo.sylloo.app/x"))
        assertNull(PushRules.address("javascript:alert(1)"))
        assertNull(PushRules.address("https://a b"))
        assertNull(PushRules.address("https://x/" + "a".repeat(2048)))
    }

    @Test
    fun onlyPlainCommandsRunInTheBackground() {
        assertEquals("sylloo-app-native:send-waiting", SyncRules.command(null))
        assertEquals("app:send", SyncRules.command("app:send"))
        assertNull(SyncRules.command("app:send --force"))
        assertNull(SyncRules.command("app:send; rm -rf /"))
        assertNull(SyncRules.command("A"))
        assertEquals(0L, SyncRules.periodMinutes(0))
        assertEquals(15L, SyncRules.periodMinutes(5))
        assertEquals(1440L, SyncRules.periodMinutes(100_000))
    }

    @Test
    fun updatesComeOnlyFromTheReleaseHostsWithTheirSha() {
        assertTrue(UpdateRules.isAllowedUrl("https://github.com/rishadblack/x/releases/download/v1/app.apk"))
        assertTrue(UpdateRules.isAllowedUrl("https://objects.githubusercontent.com/x"))
        assertFalse(UpdateRules.isAllowedUrl("http://github.com/x"))
        assertFalse(UpdateRules.isAllowedUrl("https://example.com/x.apk"))
        assertFalse(UpdateRules.isAllowedUrl("https://user@github.com/x"))
        assertFalse(UpdateRules.isAllowedUrl(null))
        assertEquals("ab".repeat(32), UpdateRules.sha("AB".repeat(32)))
        assertNull(UpdateRules.sha("abc"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateRules.sha256("abc".byteInputStream())
        )
    }

    @Test
    fun savedFileNamesHaveNoFolders() {
        assertEquals("report.pdf", WindowFileNames.safe("../../report.pdf"))
        assertEquals("a_b.pdf", WindowFileNames.safe("a:b.pdf"))
        assertEquals("download", WindowFileNames.safe(".."))
        assertEquals("download", WindowFileNames.safe(null))
        assertEquals(120, WindowFileNames.safe("a".repeat(300)).length)
    }
}
