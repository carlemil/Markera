package markera.server

import io.ktor.server.application.ApplicationCall
import java.net.InetAddress
import java.time.LocalDate
import java.time.ZoneOffset
import org.slf4j.Logger

/** `PUT /series/{id}` per user per hour. The app saves on every tap and marker drag, so this sits well above a long edit session. */
const val MAX_SERIES_EDITS_PER_HOUR = 300

/**
 * Deleted holes one series may hold in total. A scan's own removals ([MAX_DELETED_HOLES]) plus deleting every live
 * hole later ([MAX_HOLES]) stays within it, so the app's flows never reach it.
 */
const val MAX_STORED_DELETED_HOLES = MAX_DELETED_HOLES + MAX_HOLES

/** Non-baseline audit rows kept per series: the newest ones. Baseline rows are always kept. */
const val MAX_AUDIT_EDITS = 50

/** `/auth/apple/start` and `/auth/apple/callback`, each, per client IP per [APPLE_WEB_WINDOW_MILLIS]. */
const val MAX_APPLE_WEB_PER_WINDOW = 20
const val APPLE_WEB_WINDOW_MILLIS = 10 * 60_000L

/** How long a state from `/auth/apple/start` stays valid for the callback (single use). */
const val APPLE_STATE_TTL_MILLIS = 10 * 60_000L

/** Accepted suggestions per client IP per 24 h; [MAX_SUGGESTIONS_PER_DAY] still caps everyone together. */
const val MAX_SUGGESTIONS_PER_SENDER_PER_DAY = 5

/** Thrown inside a store transaction (which then rolls back) when a request would pass a stored-size limit; answered 400. */
class LimitExceededException(message: String) : IllegalArgumentException(message)

/**
 * At most [max] events per key in any [windowMillis]. In memory: one container, and a restart starting every key
 * afresh costs nothing. At most [maxKeys] keys are tracked; past that the least recently added key is forgotten.
 */
class RateLimiter(
    private val max: Int,
    private val windowMillis: Long,
    private val maxKeys: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val hits = LinkedHashMap<String, ArrayDeque<Long>>()

    /** Records one event for [key]: null when it is allowed, else the seconds until the next one will be. */
    @Synchronized
    fun acquire(key: String): Long? {
        val now = clock()
        val times = hits[key] ?: ArrayDeque<Long>().also {
            if (hits.size >= maxKeys) {
                hits.values.removeIf { t -> t.isEmpty() || t.last() <= now - windowMillis }
                if (hits.size >= maxKeys) hits.remove(hits.keys.first())
            }
            hits[key] = it
        }
        while (times.isNotEmpty() && times.first() <= now - windowMillis) times.removeFirst()
        if (times.size >= max) return ((times.first() + windowMillis - now + 999) / 1000).coerceAtLeast(1)
        times.addLast(now)
        return null
    }
}

/**
 * The `state` values `/auth/apple/start` handed out, each good for one callback within [ttlMillis]. Bounded like
 * [RateLimiter]: past [maxEntries] the oldest is dropped.
 */
class IssuedStates(
    private val ttlMillis: Long = APPLE_STATE_TTL_MILLIS,
    private val maxEntries: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val expiries = LinkedHashMap<String, Long>()

    @Synchronized
    fun issue(state: String) {
        val now = clock()
        expiries.values.removeIf { it < now }
        expiries.remove(state)
        if (expiries.size >= maxEntries) expiries.remove(expiries.keys.first())
        expiries[state] = now + ttlMillis
    }

    /** True once for a state issued within the TTL; it is gone afterwards either way. */
    @Synchronized
    fun consume(state: String): Boolean = expiries.remove(state)?.let { it >= clock() } ?: false
}

/** Bytes accepted per UTC day, in memory (nothing stores photo sizes); a restart starts the day's count afresh. */
class DailyBytes(private val today: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) }) {
    private var day: LocalDate? = null
    private var used = 0L

    @Synchronized
    fun used(): Long = if (day == today()) used else 0

    /** Adds [bytes] unless that would pass [max]; false (and nothing added) then. */
    @Synchronized
    fun tryAdd(bytes: Long, max: Long): Boolean {
        val now = today()
        if (day != now) { day = now; used = 0 }
        if (used + bytes > max) return false
        used += bytes
        return true
    }
}

/** A warning per key at most once per UTC day, so a cap that stays hit does not fill the log. */
class DailyWarnings(private val log: Logger, private val today: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) }) {
    private val warned = HashMap<String, LocalDate>()

    fun warn(key: String, message: String) {
        val now = today()
        val first = synchronized(warned) { warned.put(key, now) != now }
        if (first) log.warn(message)
    }
}

// clientIp and isPrivateAddress live in AdminSecurity.kt, shared with the admin login throttle.
