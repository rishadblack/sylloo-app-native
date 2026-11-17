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
    fun limitsPageScriptsToTheBusinessOrigins() {
        assertEquals(setOf("https://demo.sylloo.app"), WindowFilter.origins(listOf("DEMO.sylloo.app", ""), false))
        assertEquals(setOf("https://demo.sylloo.app", "http://demo.sylloo.app"), WindowFilter.origins(listOf("demo.sylloo.app"), true))
    }
}
