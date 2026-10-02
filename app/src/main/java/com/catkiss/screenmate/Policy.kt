package com.catkiss.screenmate

import java.net.URI
import kotlin.math.min

/** Pure policy, shared by production and deterministic tests. Times are monotonic. */
class WatchPolicy {
    var active = false
        private set
    var epoch = 0L
        private set
    private var lastComment = Long.MIN_VALUE / 2
    private var lastUser = Long.MIN_VALUE / 2
    var nextVisionAt = 0L
        private set
    private var failures = 0
    fun start() { epoch++; active = true; nextVisionAt = 0 }
    fun invalidate() { epoch++; active = false }
    fun accepts(ticket: Long) = active && epoch == ticket
    fun user(now: Long) { lastUser = now }
    fun commented(now: Long) { lastComment = now }
    fun mayComment(now: Long, observed: Long, interesting: Boolean, interval: Long): Boolean =
        active && interesting && now - observed in 0..60_000 && now - lastUser >= 45_000 && now - lastComment >= interval
    fun mayObserve(now: Long) = active && now >= nextVisionAt
    fun observed(now: Long, interval: Long) { failures = 0; nextVisionAt = now + interval }
    fun failed(now: Long, retrySeconds: Long? = null) {
        failures = min(failures + 1, 6)
        nextVisionAt = now + maxOf(min(300_000L, 5_000L shl failures), (retrySeconds ?: 0).coerceIn(0, 3600) * 1000)
    }
}

object Endpoints {
    const val OFFICIAL = "https://generativelanguage.googleapis.com/v1beta"
    fun validate(raw: String): Boolean = runCatching {
        val u = URI(raw)
        u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null && u.query == null && u.fragment == null
    }.getOrDefault(false)
    fun model(raw: String) = raw.matches(Regex("[A-Za-z0-9._-]{1,120}"))
}

object TextBounds {
    fun compact(raw: String, limit: Int) = raw.trim().take(limit)
    fun sameEvidence(a: String, b: String): Boolean = a.filterNot(Char::isWhitespace) == b.filterNot(Char::isWhitespace)
}
