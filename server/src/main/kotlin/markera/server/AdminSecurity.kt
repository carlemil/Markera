package markera.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Counts wrong admin passwords per client address and in total, in memory only (a restart forgets them).
 *
 * Per address: [perIpLimit] wrong passwords inside [windowMs] lock that address for [windowMs]; each further lock
 * while the address is still remembered doubles, up to [maxLockMs]. A correct password clears only its own address.
 * In total: [globalLimit] wrong passwords inside [windowMs], from any mix of addresses, pause every admin login for
 * [globalLockMs] (the correct password too, for that short while).
 */
class AdminLoginThrottle(
    private val clock: () -> Long = System::currentTimeMillis,
    private val perIpLimit: Int = 5,
    private val windowMs: Long = 15 * 60_000L,
    private val maxLockMs: Long = 24 * 60 * 60_000L,
    private val globalLimit: Int = 100,
    private val globalLockMs: Long = 5 * 60_000L,
    /** Addresses remembered at once; past this the quiet ones are forgotten first. */
    private val maxTracked: Int = 10_000,
) {
    private class Entry {
        val failures = ArrayDeque<Long>()
        var strikes = 0
        var lockedUntil = 0L
        var lastSeen = 0L
    }

    private val entries = HashMap<String, Entry>()
    private val globalFailures = ArrayDeque<Long>()
    private var globalLockedUntil = 0L

    /** Milliseconds until [ip] may try again, or null when it may try now. */
    @Synchronized
    fun lockedFor(ip: String): Long? {
        val now = clock()
        val until = maxOf(entries[ip]?.lockedUntil ?: 0L, globalLockedUntil)
        return (until - now).takeIf { it > 0 }
    }

    @Synchronized
    fun failure(ip: String) {
        val now = clock()
        val entry = entries.getOrPut(ip) { Entry() }
        entry.lastSeen = now
        entry.failures.dropOlderThan(now - windowMs)
        entry.failures.addLast(now)
        if (entry.failures.size >= perIpLimit) {
            entry.failures.clear()
            val lock = (windowMs shl minOf(entry.strikes, 20)).coerceAtMost(maxLockMs)
            entry.strikes++
            entry.lockedUntil = now + lock
        }
        globalFailures.dropOlderThan(now - windowMs)
        globalFailures.addLast(now)
        if (globalFailures.size >= globalLimit) {
            globalFailures.clear()
            globalLockedUntil = now + globalLockMs
        }
        if (entries.size > maxTracked) prune(now)
    }

    @Synchronized
    fun success(ip: String) {
        entries.remove(ip)
    }

    /** Addresses tracked right now; for tests. */
    @Synchronized
    internal fun trackedCount(): Int = entries.size

    private fun prune(now: Long) {
        // Forget an address once its lock is over and it has been quiet for a full lock span.
        entries.values.removeAll { it.lockedUntil <= now && now - it.lastSeen > maxLockMs }
        if (entries.size > maxTracked) {
            entries.entries.filter { it.value.lockedUntil <= now }
                .sortedBy { it.value.lastSeen }
                .take(entries.size - maxTracked)
                .forEach { entries.remove(it.key) }
        }
    }

    private fun ArrayDeque<Long>.dropOlderThan(cutoff: Long) {
        while (isNotEmpty() && first() <= cutoff) removeFirst()
    }
}

/**
 * The address a request comes from. The server sits behind the host's reverse proxy (compose publishes the
 * port on 127.0.0.1 only), so the socket peer is the proxy, a loopback or private address; then the rightmost
 * `X-Forwarded-For` entry, the one that proxy appended, names the client. From any other peer the header is
 * ignored and the peer itself is the address.
 */
internal fun clientIp(call: ApplicationCall): String {
    val peer = call.request.local.remoteAddress
    if (!isLoopback(peer) && !isPrivateAddress(peer)) return peer
    val forwarded = call.request.headers.getAll(HttpHeaders.XForwardedFor)
        ?.lastOrNull()
        ?.substringAfterLast(',')
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 64 }
    return forwarded ?: peer
}

/** A literal IPv4/IPv6 address in a private or link-local range (never resolves a name). */
internal fun isPrivateAddress(address: String): Boolean {
    val literal = address.isNotEmpty() && (address.contains(':') || address.all { it.isDigit() || it == '.' })
    if (!literal) return false
    val ip = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return false
    return ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress ||
        (ip is Inet6Address && (ip.address[0].toInt() and 0xfe) == 0xfc)
}

/** True when the `Host` header names this machine (`localhost`, `127.0.0.1` or `[::1]`, any port). */
internal fun isLocalHostHeader(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    val name = if (host.startsWith("[")) host.substringBefore(']') + "]" else host.substringBefore(':')
    return name.lowercase() in setOf("localhost", "127.0.0.1", "[::1]")
}

/** Interface the server binds: loopback alone for the local no-auth copy, every interface otherwise (Docker). */
fun bindHost(config: Config): String = if (config.adminLocalNoAuth) "127.0.0.1" else "0.0.0.0"

/**
 * `ADMIN_LOCAL_NO_AUTH` is for a local test copy only. Throws, with the reason, when it is combined with a public
 * origin (`PUBLIC_URL`) or a bind address other than loopback.
 */
fun requireLocalNoAuthIsLocal(config: Config, host: String) {
    if (!config.adminLocalNoAuth) return
    require(config.publicUrl == null && config.appleWeb?.publicUrl == null) {
        "ADMIN_LOCAL_NO_AUTH=true is for a local copy only and cannot be combined with PUBLIC_URL; unset one of them"
    }
    require(isLoopback(host)) {
        "ADMIN_LOCAL_NO_AUTH=true needs the server bound to a loopback address, not $host"
    }
}

/** Standard response headers on every response. Same-origin framing stays allowed: the curate page frames the editor. */
val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        call.response.headers.append("X-Frame-Options", "SAMEORIGIN")
        call.response.headers.append("Content-Security-Policy", "frame-ancestors 'self'")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
    }
}
