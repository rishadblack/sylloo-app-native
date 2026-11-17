package com.rishadblack.plugins.sylloo_app_native

import java.net.URI

/**
 * What the cloud window does with an address it is asked to open.
 *
 * - SHOW: the window shows it (one of the business's hosts, under an allowed path).
 * - LEAVE: it opens in the normal browser (another site, or a page of the business the window may not show; a
 *   `mailto:` / `tel:` link opens its app).
 * - ENDED: the business sent the window to a page it may not show by a redirect: its session ended (a sign-in page);
 *   the app asks for a new one-use ticket (quietly, within the base's limit) instead of opening the browser.
 * - BLOCK: nothing happens (`javascript:`, `file:`, `content:`, `intent:`, a page-made `data:` or `blob:` page).
 */
enum class WindowDecision { SHOW, LEAVE, ENDED, BLOCK }

/**
 * The cloud window's filter (pure, JVM-tested). It is the Kotlin twin of the shared base's
 * `Rishadblack\SyllooAppBase\Window\WindowRules::mayShow()`: the same rule, checked on the phone before every
 * top-frame navigation; both are tested against the same cases (`tests/Fixtures/window-cases.json`).
 */
object WindowFilter {
    private val OUTSIDE_SCHEMES = setOf("mailto", "tel", "sms", "geo")

    fun decide(url: String?, hosts: List<String>, paths: List<String>, allowHttp: Boolean, isRedirect: Boolean): WindowDecision {
        val uri = parse(url) ?: return WindowDecision.BLOCK
        val scheme = uri.scheme?.lowercase() ?: return WindowDecision.BLOCK

        if (scheme in OUTSIDE_SCHEMES) return WindowDecision.LEAVE
        if (scheme != "https" && scheme != "http") return WindowDecision.BLOCK

        if (mayShow(url, hosts, paths, allowHttp)) return WindowDecision.SHOW

        val host = uri.host?.lowercase()
        val businessHost = host != null && host in hosts.map { it.lowercase() }

        return if (businessHost && isRedirect) WindowDecision.ENDED else WindowDecision.LEAVE
    }

    /**
     * WindowRules::mayShow(): https (http only outside production), no user in the address, one of the hosts, a path
     * under one of the allowed paths, no `..` step.
     */
    fun mayShow(url: String?, hosts: List<String>, paths: List<String>, allowHttp: Boolean): Boolean {
        val uri = parse(url) ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false

        if (!(scheme == "https" || (scheme == "http" && allowHttp))) return false
        if (uri.rawUserInfo != null) return false

        val host = uri.host?.lowercase() ?: return false
        if (host !in hosts.filter { it.isNotEmpty() }.map { it.lowercase() }) return false

        val path = "/" + (uri.rawPath ?: "/").trimStart('/')
        if (path.contains("/../") || path.endsWith("/..")) return false

        for (allowed in paths) {
            val normal = "/" + allowed.trim('/')
            if (path == normal || path.startsWith(normal.trimEnd('/') + "/")) return true
        }

        return false
    }

    /** The origins (`https://host`) the window's page scripts and messages are limited to. */
    fun origins(hosts: List<String>, allowHttp: Boolean): Set<String> =
        hosts.filter { it.isNotEmpty() }.flatMap { host ->
            if (allowHttp) listOf("https://${host.lowercase()}", "http://${host.lowercase()}") else listOf("https://${host.lowercase()}")
        }.toSet()

    private fun parse(url: String?): URI? {
        if (url.isNullOrBlank() || url.any { it.isWhitespace() }) return null
        return try {
            URI(url).takeIf { it.scheme != null }
        } catch (e: Exception) {
            null
        }
    }
}
