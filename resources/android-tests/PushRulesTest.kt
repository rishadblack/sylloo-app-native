package com.rishadblack.plugins.sylloo_app_native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** A push with a screen and buttons (device-api.md §1.1): `open`, `open_id`, `actions`, and the app's label map. */
class PushRulesTest {

    private val labels = mapOf("crm.call" to "Call")

    @Test
    fun aButtonIsShownOnlyWithTheAppsLabel() {
        val actions = PushRules.actions("""[{"key":"call","label":"crm.call"},{"key":"later","label":"crm.later"}]""", labels)

        assertEquals(listOf("call" to "Call"), actions)
        assertEquals(emptyList<Pair<String, String>>(), PushRules.actions("""[{"key":"call","label":"crm.call"}]""", emptyMap()))
    }

    @Test
    fun badButtonsAndBadJsonAreLeftOut() {
        assertEquals(emptyList<Pair<String, String>>(), PushRules.actions("not json", labels))
        assertEquals(emptyList<Pair<String, String>>(), PushRules.actions(null, labels))
        assertEquals(emptyList<Pair<String, String>>(), PushRules.actions("""[{"key":"Call Now","label":"crm.call"}]""", labels))
        assertEquals(1, PushRules.actions("""[{"key":"call","label":"crm.call"},{"key":"call","label":"crm.call"}]""", labels).size)
    }

    @Test
    fun theLabelMapKeepsOnlyGoodKeysAndShortTexts() {
        val map = PushRules.labels(mapOf("crm.call" to " Call ", "Bad Key" to "x", "crm.empty" to " ", "crm.long" to "y".repeat(80)))

        assertEquals("Call", map["crm.call"])
        assertFalse(map.containsKey("Bad Key"))
        assertFalse(map.containsKey("crm.empty"))
        assertEquals(PushRules.MAX_LABEL, map["crm.long"]!!.length)
    }

    @Test
    fun aTapKeepsTheScreenTheRecordAndTheButton() {
        val opened = PushRules.opened(null, "lead", "01HX-1", "call")!!

        assertEquals("lead", opened.getString("open"))
        assertEquals("01HX-1", opened.getString("open_id"))
        assertEquals("call", opened.getString("action"))
        assertFalse(opened.has("url"))

        val tap = PushRules.opened("https://demo.sylloo.app/employee", "lead", "../x y", null)!!
        assertEquals("https://demo.sylloo.app/employee", tap.getString("url"))
        assertFalse(tap.has("open_id"))
        assertEquals("open", tap.getString("action"))

        assertNull(PushRules.opened("javascript:alert(1)", "Lead Card", null, null))
    }
}
