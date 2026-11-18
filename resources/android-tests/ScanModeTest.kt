package com.rishadblack.plugins.sylloo_app_native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continuous scan on / off on the camera screen: the person's choice is kept on the phone and wins over the app's
 * default next time; off closes after the first counted code.
 */
class ScanModeTest {
    private class MapStore : ScanSettingStore {
        val values = HashMap<String, Boolean>()

        override fun get(key: String): Boolean? = values[key]

        override fun put(key: String, value: Boolean) {
            values[key] = value
        }
    }

    @Test
    fun usesTheAppDefaultTheFirstTime() {
        assertTrue(ScanMode(MapStore(), appDefault = true).continuous)
        assertFalse(ScanMode(MapStore(), appDefault = false).continuous)
    }

    @Test
    fun keepsThePersonsChoiceAndUsesItNextTimeWhateverTheAppSays() {
        val store = MapStore()
        ScanMode(store, appDefault = true).change(false)

        assertEquals(false, store.values[ScanMode.KEY])
        assertFalse(ScanMode(store, appDefault = true).continuous)

        ScanMode(store, appDefault = false).change(true)
        assertTrue(ScanMode(store, appDefault = false).continuous)
    }

    @Test
    fun closesAfterTheFirstReadOnlyWhenOff() {
        val mode = ScanMode(MapStore(), appDefault = true)
        assertFalse(mode.closesAfterRead())
        mode.change(false)
        assertTrue(mode.closesAfterRead())
        // Turned on again mid-session: the screen stays open again.
        mode.change(true)
        assertFalse(mode.closesAfterRead())
    }

    @Test
    fun aCallerThatHidesTheSwitchDecidesAloneAndNothingIsKept() {
        val store = MapStore()
        store.values[ScanMode.KEY] = true

        val mode = ScanMode(store, appDefault = false, switchable = false)
        assertFalse(mode.continuous)
        assertTrue(mode.closesAfterRead())

        mode.change(true)
        assertFalse(mode.continuous)
        assertEquals(true, store.values[ScanMode.KEY])

        val fresh = MapStore()
        ScanMode(fresh, appDefault = true, switchable = false).change(false)
        assertNull(fresh.values[ScanMode.KEY])
    }

    @Test
    fun withContinuousOffOnlyOneCodeIsSentEvenWhenTwoComeQuickly() {
        // The screen's loop as in BarcodeScanActivity: guard, then the mode; after the one code nothing more counts.
        val mode = ScanMode(MapStore(), appDefault = false)
        val guard = ScanRepeatGuard()
        var stopped = false
        val sent = mutableListOf<String>()
        for ((code, at) in listOf("A1" to 0L, "B2" to 40L, "A1" to 2_000L)) {
            if (stopped) continue
            if (guard.accept(code, at)) {
                if (mode.closesAfterRead()) stopped = true
                sent += code
            }
        }
        assertEquals(listOf("A1"), sent)
    }

    @Test
    fun theSwitchAndDoneSpeakTheAppLanguageAndFallBackToEnglish() {
        assertEquals("একটানা স্ক্যান", ScanLabels.text("একটানা স্ক্যান", ScanLabels.CONTINUOUS))
        assertEquals("Continuous scan", ScanLabels.text("  ", ScanLabels.CONTINUOUS))
        assertEquals("Done", ScanLabels.text(null, ScanLabels.DONE))
        assertEquals("Valmis", ScanLabels.text(" Valmis ", ScanLabels.DONE))
        assertEquals(ScanLabels.MAX, ScanLabels.text("x".repeat(90), ScanLabels.DONE).length)
    }

    @Test
    fun theSwitchStateFollowsTheModeAndIsReadOnOrOff() {
        val mode = ScanMode(MapStore(), appDefault = true)
        assertEquals("চালু", ScanLabels.state(mode.continuous, "চালু", "বন্ধ"))
        mode.change(false)
        assertFalse(mode.continuous)
        assertEquals("বন্ধ", ScanLabels.state(mode.continuous, "চালু", "বন্ধ"))
        assertEquals("Off", ScanLabels.state(false, null, ""))
        assertEquals("On", ScanLabels.state(true, null, null))
    }
}
