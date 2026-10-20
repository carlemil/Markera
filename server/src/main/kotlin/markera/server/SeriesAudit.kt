package markera.server

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Supplied by authenticated routes, never by a request body. Basic auth identifies a role, not a person. */
data class AuditActor(val type: String, val id: String) {
    companion object {
        fun app(userId: Long) = AuditActor("app-user", userId.toString())
        val Admin = AuditActor("admin", "admin")
    }
}

data class AuditEvent(
    val id: Long, val seriesId: Long, val ownerId: Long, val at: String,
    val actor: AuditActor, val operation: String, val baseline: Boolean,
    val before: String?, val after: String,
) {
    val reasons: List<String> get() {
        val old = before?.let { Json.parseToJsonElement(it).jsonObject } ?: return emptyList()
        val new = Json.parseToJsonElement(after).jsonObject
        return new.keys.filter { old[it] != new[it] }
    }
}

internal val auditJson = Json { encodeDefaults = true }
internal val auditTime = java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter()

/** Persisted values only. Sorting retains duplicate holes but ignores row IDs and request order. */
internal fun auditState(s: Series, fingerprint: String?): String = buildJsonObject {
    put("timestamp", s.timestamp)
    put("caliber", s.caliber.trim())
    put("tag", s.tag?.trim()?.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
    put("geometry", auditJson.encodeToJsonElement(s.geometry))
    put("holes", JsonArray(s.holes.map { auditJson.encodeToJsonElement(it) }.sortedBy { it.toString() }))
    put("photo", fingerprint?.let(::JsonPrimitive) ?: JsonNull)
    put("dimensions", buildJsonArray { add(s.imageWidth?.let(::JsonPrimitive) ?: JsonNull); add(s.imageHeight?.let(::JsonPrimitive) ?: JsonNull) })
    put("deleted", s.deleted)
}.toString()

internal fun photoHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

/** Shared classification for existing edited counts and inferred historical review candidates. */
internal fun holeSignals(h: Hole): List<String> = buildList {
    if (h.deleted) add("deleted detection")
    if (h.detectedRing == null) add(if (h.x == null) "typed hole" else "manual hole")
    else if (h.ring != h.detectedRing || h.innerTen != (h.detectedInnerTen == true)) add("score/inner-X override")
    if (h.detectedX != null && (h.x != h.detectedX || h.y != h.detectedY)) add("moved hole")
}

/** SQL counterpart to [holeSignals], used only to bound the page before loading full rows/history. */
internal const val EDITED_HOLE_SQL = """h.deleted = 1 OR h.detected_ring IS NULL OR h.ring != h.detected_ring
    OR h.inner_ten != COALESCE(h.detected_inner_ten, 0)
    OR (h.detected_x IS NOT NULL AND (h.x IS NOT h.detected_x OR h.y IS NOT h.detected_y))"""

data class ReviewRow(val series: Series, val owner: UserRow, val events: List<AuditEvent>)
