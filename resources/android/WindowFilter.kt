package com.rishadblack.plugins.sylloo_app_native

import java.net.URI

/**
 * What the cloud window does with an address it is asked to open.
 *
 * - SHOW: the window shows it (one of the business's hosts, under an allowed path).
 * - LEAVE: it opens in the normal browser (another site, or a page of the business the window may not show; a
 *   `mailto:` / `tel:` link opens its app).
 * - ENDED: the business's sign-in page (`/member/login`, `/login`), or a redirect to a page of the business the window
 *   may not show: its session ended; the app asks for a new one-use ticket (quietly, within the base's limit) instead
 *   of showing that page or opening the browser.
 * - SIGN_OUT: the page's own Sign out (`/member/theme/logout`, `/member/logout`, `/logout`): the app signs this member
 *   out of the app too, so the sign-in page that follows never signs them in again quietly (desktop: `signOut`).
 * - BLOCK: nothing happens (`javascript:`, `file:`, `content:`, `intent:`, a page-made `data:` or `blob:` page).
 */
enum class WindowDecision { SHOW, LEAVE, ENDED, SIGN_OUT, BLOCK }

/**
 * The cloud window's filter (pure, JVM-tested). It is the Kotlin twin of the shared base's
 * `Rishadblack\SyllooAppBase\Window\WindowRules::mayShow()`: the same rule, checked on the phone before every
 * top-frame navigation; both are tested against the same cases (`tests/Fixtures/window-cases.json`).
 */
object WindowFilter {
    private val OUTSIDE_SCHEMES = setOf("mailto", "tel", "sms", "geo")

    /** The business's sign-in pages (the same list as the desktop app's `SIGN_IN_PATHS`). */
    val SIGN_IN_PATHS = listOf("/member/login", "/login")

    /** The business's sign-out addresses (the same list as the desktop app's `SIGN_OUT_PATHS`). */
    val SIGN_OUT_PATHS = listOf("/member/theme/logout", "/member/logout", "/logout")

    fun decide(url: String?, hosts: List<String>, paths: List<String>, allowHttp: Boolean, isRedirect: Boolean): WindowDecision {
        val uri = parse(url) ?: return WindowDecision.BLOCK
        val scheme = uri.scheme?.lowercase() ?: return WindowDecision.BLOCK

        if (scheme in OUTSIDE_SCHEMES) return WindowDecision.LEAVE
        if (scheme != "https" && scheme != "http") return WindowDecision.BLOCK

        val host = uri.host?.lowercase()
        val businessHost = host != null && host in hosts.filter { it.isNotEmpty() }.map { it.lowercase() }

        if (businessHost && uri.rawUserInfo == null) {
            val path = "/" + (uri.rawPath ?: "/").trimStart('/')
            if (SIGN_OUT_PATHS.any { under(path, it) }) return WindowDecision.SIGN_OUT
            if (SIGN_IN_PATHS.any { under(path, it) }) return WindowDecision.ENDED
        }

        if (mayShow(url, hosts, paths, allowHttp)) return WindowDecision.SHOW

        return if (businessHost && isRedirect) WindowDecision.ENDED else WindowDecision.LEAVE
    }

    /** How long after the page's own Sign out was sent its answer still counts as that sign-out. */
    const val SIGN_OUT_ANSWER_MS = 15_000L

    /**
     * What a navigation means right after the page's own Sign out was sent (`msSinceSignOut`, null when none was).
     * Android shows the window the Sign out POST only once the business answered it (a redirect to its sign-in page; on
     * Practice the Live sign-in address), so that answer is the sign-out: SIGN_OUT, never ENDED (a quiet new ticket
     * would sign the member in again) or LEAVE (the browser). A page the window shows stays SHOW.
     */
    fun afterSignOut(decision: WindowDecision, msSinceSignOut: Long?): WindowDecision {
        if (msSinceSignOut == null || msSinceSignOut < 0 || msSinceSignOut > SIGN_OUT_ANSWER_MS) return decision

        return if (decision == WindowDecision.ENDED || decision == WindowDecision.LEAVE) WindowDecision.SIGN_OUT else decision
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

    /** `/member/login` matches `/member/login` and `/member/login/…`, never `/member/loginx`. */
    private fun under(path: String, prefix: String): Boolean {
        val normal = "/" + prefix.trim('/')
        return path.trimEnd('/') == normal || path.startsWith("$normal/")
    }

    /**
     * A left address that the phone's own app handles (app-plan §8.1): `tel:`, `mailto:`, `sms:`, `geo:` and WhatsApp's
     * `https://wa.me/…` / `https://api.whatsapp.com/send…` open with Android's own chooser (the dialler, mail, messages,
     * maps, WhatsApp) instead of a browser tab. Everything else that leaves goes to the normal browser.
     */
    fun opensPhoneApp(url: String?): Boolean {
        val uri = parse(url) ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false

        if (scheme in OUTSIDE_SCHEMES) return true
        if (scheme != "https") return false

        val host = uri.host?.lowercase() ?: return false

        return host == "wa.me" || host == "api.whatsapp.com"
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
