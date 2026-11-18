package com.rishadblack.plugins.sylloo_app_native

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cloud window's filter against the shared cases (`tests/Fixtures/window-cases.json`, copied here by the build
 * hook): the same file the PHP rules (WindowRules::mayShow, WindowSession::decide) are tested with.
 */
class WindowFilterTest {

    private fun cases(): JSONArray {
        val text = javaClass.classLoader!!.getResource("window-cases.json")!!.readText()
        return JSONObject(text).getJSONArray("cases")
    }

    private fun list(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    @Test
    fun agreesWithThePhpRulesOnEveryCase() {
        val all = cases()
        assertTrue(all.length() > 10)

        for (i in 0 until all.length()) {
            val case = all.getJSONObject(i)
            val name = case.getString("name")
            val url = case.getString("url")
            val hosts = list(case.getJSONArray("hosts"))
            val paths = list(case.getJSONArray("paths"))
            val allowHttp = case.getBoolean("allow_http")

            assertEquals(name, case.getBoolean("show"), WindowFilter.mayShow(url, hosts, paths, allowHttp))
            assertEquals(name, case.getString("decide").uppercase(), WindowFilter.decide(url, hosts, paths, allowHttp, false).name)
            assertEquals("$name (redirect)", case.getString("decide_redirect").uppercase(), WindowFilter.decide(url, hosts, paths, allowHttp, true).name)
        }
    }

    @Test
    fun countsTheAnswerToThePagesSignOutAsTheSignOut() {
        val hosts = listOf("new.practice.sylloo.app", "new.sylloo.app")
        val paths = listOf("/member")
        val toSignIn = WindowFilter.decide("https://new.practice.sylloo.app/member/login", hosts, paths, false, true)
        val elsewhere = WindowFilter.decide("https://login.example.com/member/login", hosts, paths, false, true)

        assertEquals(WindowDecision.ENDED, toSignIn)
        assertEquals(WindowDecision.LEAVE, elsewhere)

        // Right after the Sign out was sent, its answer (the sign-in page, or another address) is the sign-out.
        assertEquals(WindowDecision.SIGN_OUT, WindowFilter.afterSignOut(toSignIn, 300L))
        assertEquals(WindowDecision.SIGN_OUT, WindowFilter.afterSignOut(elsewhere, 300L))
        assertEquals(WindowDecision.SHOW, WindowFilter.afterSignOut(WindowDecision.SHOW, 300L))
        assertEquals(WindowDecision.BLOCK, WindowFilter.afterSignOut(WindowDecision.BLOCK, 300L))

        // No Sign out sent, or too long ago: unchanged.
        assertEquals(WindowDecision.ENDED, WindowFilter.afterSignOut(toSignIn, null))
        assertEquals(WindowDecision.ENDED, WindowFilter.afterSignOut(toSignIn, WindowFilter.SIGN_OUT_ANSWER_MS + 1))
        assertEquals(WindowDecision.LEAVE, WindowFilter.afterSignOut(elsewhere, -5L))
    }

    @Test
    fun limitsPageScriptsToTheBusinessOrigins() {
        assertEquals(setOf("https://demo.sylloo.app"), WindowFilter.origins(listOf("DEMO.sylloo.app", ""), false))
        assertEquals(setOf("https://demo.sylloo.app", "http://demo.sylloo.app"), WindowFilter.origins(listOf("demo.sylloo.app"), true))
    }

    @Test
    fun opensThePhonesOwnAppOnlyForCallsMailMessagesMapsAndWhatsApp() {
        for (url in listOf("tel:+8801711000000", "mailto:hr@example.com", "sms:+8801711000000", "geo:23.8,90.4", "https://wa.me/8801711000000", "https://api.whatsapp.com/send?phone=880")) {
            assertTrue(url, WindowFilter.opensPhoneApp(url))
        }
        for (url in listOf("http://wa.me/880", "https://example.com/wa.me", "https://wa.me.example.com/x", "intent://scan#Intent;end", "javascript:alert(1)", "file:///sdcard/x", "", null)) {
            assertEquals(url ?: "null", false, WindowFilter.opensPhoneApp(url))
        }
    }
}
