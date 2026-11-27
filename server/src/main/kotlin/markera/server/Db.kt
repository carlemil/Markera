package markera.server

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import kotlinx.serialization.json.Json

private val photoLog = org.slf4j.LoggerFactory.getLogger("markera.server.Photos")

/** The files this server keeps per series in the images directory. */
private val PHOTO_FILE = Regex("([0-9]+)\\.(jpg|previous|journal)")

/** Deletes [file] if it is there. A failed delete is logged, never thrown: the startup sweep retries it. */
private fun deletePhotoFile(file: File, why: String) {
    runCatching { java.nio.file.Files.deleteIfExists(file.toPath()) }
        .onSuccess { if (it) photoLog.info("deleted {} {}", why, file.name) }
        .onFailure { photoLog.error("could not delete {} {}", why, file.name, it) }
}

private fun Hole.spot() = detectedX?.let { x -> detectedY?.let { y -> x to y } }

/** The stored training copy: every field written out, unknown ones tolerated so an older row still reads. */
private val trainingJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** A user plus how many series they have; only the admin pages need it. */
data class UserRow(
    val id: Long,
    val provider: String,
    val subject: String,
    val name: String?,
    val createdAt: String,
    val seriesCount: Int,
    val lastUsedAt: String?,
)

/**
 * SQLite storage. Schema is created on first use.
 *
 * ponytail: single connection, add a pool if it ever contends.
 */
class Db(dbPath: String) : AutoCloseable {

    private val conn: Connection

    init {
        File(dbPath).absoluteFile.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA foreign_keys = ON")
        }
        // One transaction: a migration cut short (the holes rebuild drops a table) rolls back whole.
        transaction { conn.createStatement().use { st ->
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS users (
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     provider TEXT NOT NULL,
                     subject TEXT NOT NULL,
                     created_at TEXT NOT NULL,
                     name TEXT,
                     last_used_at TEXT, -- last sign-in or authenticated request; outlives sign-out and session expiry
                     UNIQUE(provider, subject))"""
            )
            // Databases created before names existed.
            val columns = st.executeQuery("PRAGMA table_info(users)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
            if ("name" !in columns) st.executeUpdate("ALTER TABLE users ADD COLUMN name TEXT")
            // Names once fell back to the whole e-mail address; keep only what precedes the '@'.
            st.executeUpdate("UPDATE users SET name = substr(name, 1, instr(name, '@') - 1) WHERE instr(name, '@') > 0")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS sessions (
                     token TEXT PRIMARY KEY,
                     user_id INTEGER NOT NULL REFERENCES users(id),
                     created_at TEXT NOT NULL,
                     last_used_at TEXT NOT NULL)"""
            )
            // Databases created before sessions expired and were hashed (both came together): every existing
            // session starts its 90 idle days now, so the deploy logs nobody out, and its plain token becomes the hash.
            // All at once, not lazily on use: a lazy rewrite would also re-hash a *hash* sent as a token, making
            // the stored values usable again.
            val sessionColumns = st.executeQuery("PRAGMA table_info(sessions)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
            if ("last_used_at" !in sessionColumns) {
                val plain = st.executeQuery("SELECT token FROM sessions").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
                conn.prepareStatement("UPDATE sessions SET token = ? WHERE token = ?").use { up ->
                    for (token in plain) { up.setString(1, sha256(token)); up.setString(2, token); up.executeUpdate() }
                }
                st.executeUpdate("ALTER TABLE sessions ADD COLUMN last_used_at TEXT NOT NULL DEFAULT ''")
                st.executeUpdate("UPDATE sessions SET last_used_at = datetime('now')")
            }
            // Databases created before users kept their own last use: start from their freshest session.
            if ("last_used_at" !in columns) {
                st.executeUpdate("ALTER TABLE users ADD COLUMN last_used_at TEXT")
                st.executeUpdate(
                    "UPDATE users SET last_used_at = (SELECT MAX(last_used_at) FROM sessions WHERE user_id = users.id)"
                )
            }
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS series (
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     user_id INTEGER NOT NULL REFERENCES users(id),
                     timestamp TEXT NOT NULL,
                     caliber TEXT NOT NULL,
                     created_at TEXT NOT NULL,
                     updated_at TEXT NOT NULL,
                     deleted_at TEXT,
                     image_width INTEGER,
                     image_height INTEGER,
                     tag TEXT,
                     client_id TEXT,
                     ${GEOMETRY_COLUMNS.joinToString(", ") { "$it REAL" }})"""
            )
            // Databases created before the image carried the frame size.
            val seriesColumns = st.executeQuery("PRAGMA table_info(series)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
            if ("image_width" !in seriesColumns) {
                st.executeUpdate("ALTER TABLE series ADD COLUMN image_width INTEGER")
                st.executeUpdate("ALTER TABLE series ADD COLUMN image_height INTEGER")
            }
            // Databases created before the app sent the target geometry it scored against.
            if ("centre_x" !in seriesColumns) {
                for (column in GEOMETRY_COLUMNS) st.executeUpdate("ALTER TABLE series ADD COLUMN $column REAL")
            }
            // Databases created before the delta sync (updated_at) and soft deletes (deleted_at).
            if ("updated_at" !in seriesColumns) {
                // ALTER TABLE cannot add a NOT NULL column without a default; every row is then backfilled.
                st.executeUpdate("ALTER TABLE series ADD COLUMN updated_at TEXT NOT NULL DEFAULT ''")
                st.executeUpdate("ALTER TABLE series ADD COLUMN deleted_at TEXT")
                st.executeUpdate("UPDATE series SET updated_at = created_at")
            }
            // Databases created before series carried a free-text tag; nullable, so existing rows need no backfill.
            if ("tag" !in seriesColumns) st.executeUpdate("ALTER TABLE series ADD COLUMN tag TEXT")
            // Databases created before POST /series was idempotent. NULLs never collide, so old rows and old apps
            // (no clientId) are unaffected.
            if ("client_id" !in seriesColumns) st.executeUpdate("ALTER TABLE series ADD COLUMN client_id TEXT")
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS series_user_client ON series(user_id, client_id)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS holes ($HOLE_COLUMNS)")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS series_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE,
                owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                at TEXT NOT NULL, actor_type TEXT NOT NULL, actor_id TEXT NOT NULL,
                operation TEXT NOT NULL, baseline INTEGER NOT NULL,
                before_state TEXT, after_state TEXT NOT NULL)""")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS audit_series ON series_audit(series_id, id)")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS series_photo_baseline (
                series_id INTEGER PRIMARY KEY REFERENCES series(id) ON DELETE CASCADE,
                fingerprint TEXT NOT NULL)""")
            // The admin's verdict on a series for the training data (/admin/curate); no row = not looked at yet.
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS series_curation (
                series_id INTEGER PRIMARY KEY REFERENCES series(id) ON DELETE CASCADE,
                status TEXT NOT NULL CHECK (status IN ('approved', 'rejected')),
                at TEXT NOT NULL)""")
            // Series the admin skipped in /admin/curate: never offered there again. No verdict, so the exports still
            // treat them as not reviewed; a later approve or reject (through ?id=) drops the row.
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS series_curation_skipped (
                series_id INTEGER PRIMARY KEY REFERENCES series(id) ON DELETE CASCADE,
                at TEXT NOT NULL)""")
            // The admin's own copy of a series for training (edits made in /admin/curate), a SeriesRequest as JSON.
            // The user's series is never touched by it; no row = the training data is the user's version.
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS series_training (
                series_id INTEGER PRIMARY KEY REFERENCES series(id) ON DELETE CASCADE,
                request TEXT NOT NULL,
                at TEXT NOT NULL)""")
            // The ring/centre ground truth (/admin/ring-labels): the admin's hand-corrected 6/7 ring and centre of a
            // series, beside the geometry the series had before, so detector error can be measured. Apart from the
            // series on purpose: nothing the app reads ever changes. Geometry is JSON, in the stored frame's pixels.
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS ring_labels (
                series_id INTEGER PRIMARY KEY REFERENCES series(id) ON DELETE CASCADE,
                detected TEXT,
                detected_from TEXT NOT NULL,
                corrected TEXT NOT NULL,
                image_width INTEGER NOT NULL,
                image_height INTEGER NOT NULL,
                first_at TEXT NOT NULL,
                at TEXT NOT NULL)""")
            // Series skipped in /admin/ring-labels: never offered there again; a later save drops the row.
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS ring_labels_skipped (
                series_id INTEGER PRIMARY KEY REFERENCES series(id) ON DELETE CASCADE,
                at TEXT NOT NULL)""")
            // Training exports: a series goes out once. Each export is a batch; every series in it keeps the exact
            // version that went out (a Series as JSON), so a lost download can be fetched again as it was even after
            // the holes changed. A legacy batch records series exported before tracking, with nothing frozen.
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS export_batches (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at TEXT NOT NULL,
                format TEXT NOT NULL CHECK (format IN ('yolo', 'cvat', 'legacy')),
                approved INTEGER NOT NULL)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS exported_series (
                series_id INTEGER PRIMARY KEY REFERENCES series(id) ON DELETE CASCADE,
                batch_id INTEGER NOT NULL REFERENCES export_batches(id),
                frozen TEXT)""")
            // Databases created before typed/manual holes: x/y/distance_mm were NOT NULL and there were no
            // detected_* columns. SQLite cannot drop NOT NULL, so rebuild; the column check makes it idempotent.
            val holeColumns = st.executeQuery("PRAGMA table_info(holes)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
            if ("detected_ring" !in holeColumns) {
                st.executeUpdate("DROP TABLE IF EXISTS holes_new")
                st.executeUpdate("CREATE TABLE holes_new ($HOLE_COLUMNS)")
                // Holes saved before edits were recorded are the detector output verbatim.
                st.executeUpdate(
                    """INSERT INTO holes_new(id, series_id, x, y, ring, inner_ten, distance_mm, detected_ring, detected_inner_ten)
                       SELECT id, series_id, x, y, ring, inner_ten, distance_mm, ring, inner_ten FROM holes"""
                )
                st.executeUpdate("DROP TABLE holes")
                st.executeUpdate("ALTER TABLE holes_new RENAME TO holes")
            }
            // Databases created before the detector's own position was kept (the rebuild above already
            // brought the columns along, so only the backfill is left in that case).
            if ("detected_x" !in holeColumns) {
                if ("detected_ring" in holeColumns) {
                    st.executeUpdate("ALTER TABLE holes ADD COLUMN detected_x REAL")
                    st.executeUpdate("ALTER TABLE holes ADD COLUMN detected_y REAL")
                }
                // Nothing could move a marker yet, so every detected hole sits where the detector put it.
                st.executeUpdate(
                    "UPDATE holes SET detected_x = x, detected_y = y WHERE detected_x IS NULL AND detected_ring IS NOT NULL"
                )
            }
            // Databases created before holes were soft-deleted (kept as training data, hidden from every read).
            if ("deleted" !in holeColumns && "detected_ring" in holeColumns) {
                st.executeUpdate("ALTER TABLE holes ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
            // Databases created before the detector's box was kept (a rebuilt table already has them).
            val withBox = st.executeQuery("PRAGMA table_info(holes)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
            for (column in listOf("detected_w", "detected_h", "detected_conf")) {
                if (column !in withBox) st.executeUpdate("ALTER TABLE holes ADD COLUMN $column REAL")
            }
            // The app's suggestion box. user_id is null for a signed-out sender; mailed_at stays null while the
            // mail has not gone out (no SMTP configured, or it failed).
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS suggestions (
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     user_id INTEGER REFERENCES users(id),
                     title TEXT NOT NULL,
                     description TEXT NOT NULL,
                     email TEXT,
                     platform TEXT,
                     app_version TEXT,
                     created_at TEXT NOT NULL,
                     mailed_at TEXT)"""
            )
        } }
    }

    @Synchronized
    fun upsertUser(provider: String, subject: String, name: String?): Long {
        conn.prepareStatement(
            "INSERT OR IGNORE INTO users(provider, subject, created_at) VALUES (?, ?, datetime('now'))"
        ).use { it.setString(1, provider); it.setString(2, subject); it.executeUpdate() }
        if (name != null) {
            conn.prepareStatement("UPDATE users SET name = ? WHERE provider = ? AND subject = ?")
                .use { it.setString(1, name); it.setString(2, provider); it.setString(3, subject); it.executeUpdate() }
        }
        conn.prepareStatement("SELECT id FROM users WHERE provider = ? AND subject = ?").use {
            it.setString(1, provider)
            it.setString(2, subject)
            it.executeQuery().use { rs -> rs.next(); return rs.getLong(1) }
        }
    }

    /**
     * Returns the raw token; only its sha256 is stored, so a leaked database holds no usable session. Sessions
     * idle for [SESSION_IDLE] expire; creating one prunes those.
     */
    @Synchronized
    fun createSession(userId: Long): String {
        val token = ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        conn.prepareStatement(
            "INSERT INTO sessions(token, user_id, created_at, last_used_at) VALUES (?, ?, datetime('now'), datetime('now'))"
        ).use { it.setString(1, sha256(token)); it.setLong(2, userId); it.executeUpdate() }
        touchUser(userId)
        conn.createStatement().use {
            it.executeUpdate("DELETE FROM sessions WHERE last_used_at < datetime('now', '$SESSION_IDLE')")
        }
        return token
    }

    /** The session's user, sliding its expiry; null for an unknown, revoked or expired token. */
    @Synchronized
    fun userForToken(token: String): Long? {
        val hash = sha256(token)
        val userId = conn.prepareStatement(
            "SELECT user_id FROM sessions WHERE token = ? AND last_used_at >= datetime('now', '$SESSION_IDLE')"
        ).use {
            it.setString(1, hash)
            it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        } ?: return null
        transaction { // one commit, not two, on every authenticated request
            conn.prepareStatement("UPDATE sessions SET last_used_at = datetime('now') WHERE token = ?")
                .use { it.setString(1, hash); it.executeUpdate() }
            touchUser(userId)
        }
        return userId
    }

    private fun touchUser(userId: Long) {
        conn.prepareStatement("UPDATE users SET last_used_at = datetime('now') WHERE id = ?")
            .use { it.setLong(1, userId); it.executeUpdate() }
    }

    @Synchronized
    fun revokeSession(token: String) {
        conn.prepareStatement("DELETE FROM sessions WHERE token = ?").use { it.setString(1, sha256(token)); it.executeUpdate() }
    }

    /** Runs [block] as one transaction: all of it lands, or on any failure none of it. */
    private inline fun <T> transaction(block: () -> T): T {
        conn.autoCommit = false
        try {
            return block().also { conn.commit() }
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    @Synchronized
    fun insertSeries(
        userId: Long,
        timestamp: String,
        caliber: String,
        holes: List<Hole>,
        geometry: Geometry? = null,
        tag: String? = null,
        clientId: String? = null,
    ): Long {
        // A retry of a POST whose answer got lost: the series is already here (soft-deleted or not).
        if (clientId != null) {
            conn.prepareStatement("SELECT id FROM series WHERE user_id = ? AND client_id = ?").use {
                it.setLong(1, userId)
                it.setString(2, clientId)
                it.executeQuery().use { rs -> if (rs.next()) return rs.getLong(1) }
            }
        }
        return transaction {
            insertSeriesRow(userId, timestamp, caliber.trim(), holes, geometry, tag?.trim()?.ifEmpty { null }, clientId).also {
                recordAudit(it, AuditActor.app(userId), "created baseline", null, baseline = true)
            }
        }
    }

    private fun insertSeriesRow(
        userId: Long,
        timestamp: String,
        caliber: String,
        holes: List<Hole>,
        geometry: Geometry?,
        tag: String?,
        clientId: String?,
    ): Long {
        val seriesId: Long
        conn.prepareStatement(
            "INSERT INTO series(user_id, timestamp, caliber, created_at, updated_at, tag, client_id, " +
                "${GEOMETRY_COLUMNS.joinToString(", ")}) VALUES (?, ?, ?, datetime('now'), $NOW, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS
        ).use {
            it.setLong(1, userId)
            it.setString(2, timestamp)
            it.setString(3, caliber)
            it.setString(4, tag)
            it.setString(5, clientId)
            it.setGeometry(6, geometry)
            it.executeUpdate()
            it.generatedKeys.use { rs -> rs.next(); seriesId = rs.getLong(1) }
        }
        insertHoles(seriesId, holes)
        return seriesId
    }

    /**
     * Replaces a saved series wholesale: the app and the admin page both edit scores and marker positions.
     * Holes flagged [Hole.deleted] are stored but never read back, so earlier ones stay put (training data).
     */
    @Synchronized
    fun replaceSeries(seriesId: Long, req: SeriesRequest, actor: AuditActor = AuditActor.Admin) = transaction {
        val before = stateOf(seriesId)
        conn.prepareStatement(
            "UPDATE series SET timestamp = ?, caliber = ?, tag = ?, updated_at = $NOW, " +
                "${GEOMETRY_COLUMNS.joinToString(", ") { "$it = ?" }} WHERE id = ?"
        ).use {
            it.setString(1, req.timestamp)
            it.setString(2, req.caliber.trim())
            it.setString(3, req.normalisedTag())
            it.setGeometry(4, req.geometry)
            it.setLong(11, seriesId)
            it.executeUpdate()
        }
        execute("DELETE FROM holes WHERE series_id = ? AND deleted = 0", seriesId)
        // A removed detection that is back among the live holes (the admin page's Undo) is no false
        // positive any more; one that is already stored deleted is not stored twice if a client re-sends it.
        val stored = storedDeletedSpots(seriesId)
        for (h in req.holes) {
            val spot = h.detectedX?.let { x -> h.detectedY?.let { y -> x to y } } ?: continue
            if (!h.deleted && spot in stored) {
                conn.prepareStatement(
                    "DELETE FROM holes WHERE series_id = ? AND deleted = 1 AND detected_x = ? AND detected_y = ?"
                ).use { it.setLong(1, seriesId); it.setDouble(2, spot.first); it.setDouble(3, spot.second); it.executeUpdate() }
            }
        }
        val kept = storedDeletedSpots(seriesId)
        val toInsert = req.holes.filterNot { h ->
            h.deleted && h.detectedX != null && h.detectedY != null && (h.detectedX to h.detectedY) in kept
        }
        // Only a request that adds deleted holes can pass the cap; one that adds none always saves.
        val added = toInsert.count { it.deleted }
        if (added > 0 && storedDeletedCount(seriesId) + added > MAX_STORED_DELETED_HOLES) {
            throw LimitExceededException("a series keeps at most $MAX_STORED_DELETED_HOLES removed holes")
        }
        insertHoles(seriesId, toInsert)
        recordAudit(seriesId, actor, "updated", before)
    }

    private fun storedDeletedCount(seriesId: Long): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM holes WHERE series_id = ? AND deleted = 1").use {
            it.setLong(1, seriesId)
            it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    private fun storedDeletedSpots(seriesId: Long): Set<Pair<Double, Double>> = buildSet {
        conn.prepareStatement(
            "SELECT detected_x, detected_y FROM holes WHERE series_id = ? AND deleted = 1 AND detected_x IS NOT NULL AND detected_y IS NOT NULL"
        ).use { st ->
            st.setLong(1, seriesId)
            st.executeQuery().use { rs -> while (rs.next()) add(rs.getDouble(1) to rs.getDouble(2)) }
        }
    }

    private fun insertHoles(seriesId: Long, holes: List<Hole>) {
        conn.prepareStatement(
            """INSERT INTO holes(series_id, x, y, ring, inner_ten, distance_mm, detected_ring, detected_inner_ten,
                                 detected_x, detected_y, deleted, detected_w, detected_h, detected_conf)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { st ->
            for (h in holes) {
                st.setLong(1, seriesId)
                st.setObject(2, h.x)
                st.setObject(3, h.y)
                st.setInt(4, h.ring)
                st.setInt(5, if (h.innerTen) 1 else 0)
                st.setObject(6, h.distanceMm)
                st.setObject(7, h.detectedRing)
                st.setObject(8, h.detectedInnerTen?.let { if (it) 1 else 0 })
                st.setObject(9, h.detectedX)
                st.setObject(10, h.detectedY)
                st.setInt(11, if (h.deleted) 1 else 0)
                st.setObject(12, h.detectedW)
                st.setObject(13, h.detectedH)
                st.setObject(14, h.detectedConf)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    @Synchronized
    fun listUsers(): List<UserRow> = queryUsers(null)

    /** Live series and their live holes, over all users: the admin landing page's summary line. */
    @Synchronized
    fun liveTotals(): Pair<Int, Int> = conn.createStatement().use { st ->
        st.executeQuery(
            """SELECT (SELECT COUNT(*) FROM series WHERE deleted_at IS NULL),
                      (SELECT COUNT(*) FROM holes h JOIN series s ON s.id = h.series_id
                        WHERE s.deleted_at IS NULL AND h.deleted = 0)"""
        ).use { rs -> rs.next(); rs.getInt(1) to rs.getInt(2) }
    }

    @Synchronized
    fun getUser(userId: Long): UserRow? = queryUsers(userId).firstOrNull()

    /** Every user, or only [userId]. */
    private fun queryUsers(userId: Long?): List<UserRow> {
        val users = mutableListOf<UserRow>()
        conn.prepareStatement(
            """SELECT u.id, u.provider, u.subject, u.name, u.created_at, COUNT(s.id), u.last_used_at
               FROM users u LEFT JOIN series s ON s.user_id = u.id AND s.deleted_at IS NULL
               ${if (userId == null) "" else "WHERE u.id = ?"}
               GROUP BY u.id
               ORDER BY COUNT(s.id) > 0 DESC, u.name IS NULL, u.name COLLATE NOCASE, u.id DESC"""
        ).use { st ->
            if (userId != null) st.setLong(1, userId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    users += UserRow(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6),
                        rs.getString(7),
                    )
                }
            }
        }
        return users
    }

    /**
     * [includeDeleted] (the admin page) also finds a soft-deleted series, as a full row flagged [Series.deleted],
     * and brings the soft-deleted holes along, flagged — the page shows both greyed.
     */
    @Synchronized
    fun getSeries(seriesId: Long, includeDeleted: Boolean = false): Series? {
        conn.prepareStatement("$SERIES_SELECT WHERE id = ?${liveOnly(includeDeleted)}").use { st ->
            st.setLong(1, seriesId)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                return seriesRow(rs, tombstone = false).copy(holes = holesOf(seriesId, includeDeleted))
            }
        }
    }

    /**
     * Ids of every series that is not soft-deleted, rejected or skipped in /admin/curate, any user's, oldest first:
     * the default training export's input (approved and not yet reviewed series both count; a skipped one waits until
     * it is approved, which clears the skip).
     */
    @Synchronized
    fun trainingSeriesIds(): List<Long> = conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT id FROM series WHERE deleted_at IS NULL " +
                "AND id NOT IN (SELECT series_id FROM series_curation WHERE status = 'rejected') " +
                "AND id NOT IN (SELECT series_id FROM series_curation_skipped) ORDER BY id"
        ).use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
    }

    /** Live series nobody has approved or rejected yet, oldest first: what /admin/curate picks from. */
    @Synchronized
    fun uncuratedSeriesIds(): List<Long> = conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT id FROM series WHERE deleted_at IS NULL AND id NOT IN (SELECT series_id FROM series_curation) ORDER BY id"
        ).use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
    }

    /**
     * Series where the user moved or removed a detection: a hole with a detected position it no longer sits at, or a
     * deleted detection (a removed hand-placed hole does not count). The user's own holes, not the admin's training copy.
     * /admin/curate's "moved or deleted holes only" switch.
     */
    @Synchronized
    fun seriesWithCorrectedDetections(): Set<Long> = conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT DISTINCT series_id FROM holes WHERE (deleted = 1 AND detected_ring IS NOT NULL) " +
                "OR (deleted = 0 AND detected_x IS NOT NULL AND detected_y IS NOT NULL AND x IS NOT NULL AND y IS NOT NULL " +
                "AND (x <> detected_x OR y <> detected_y))"
        ).use { rs -> buildSet { while (rs.next()) add(rs.getLong(1)) } }
    }

    /** Live series approved for training, oldest first: the approved-only training export's input. */
    @Synchronized
    fun approvedSeriesIds(): List<Long> = conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT s.id FROM series s JOIN series_curation c ON c.series_id = s.id " +
                "WHERE s.deleted_at IS NULL AND c.status = 'approved' ORDER BY s.id"
        ).use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
    }

    /** Series some training export already took: never exported again. */
    @Synchronized
    fun exportedSeriesIds(): Set<Long> = conn.createStatement().use { st ->
        st.executeQuery("SELECT series_id FROM exported_series").use { rs -> buildSet { while (rs.next()) add(rs.getLong(1)) } }
    }

    /**
     * Records a new export batch of [series] (each frozen as it goes out), skipping any another export took in the
     * meantime. Returns the batch id and the series it holds; no batch when none is left.
     */
    @Synchronized
    fun recordExport(format: ExportFormat, approved: Boolean, series: List<Series>): Pair<Long, List<Series>>? = transaction {
        val taken = exportedSeriesIds()
        val fresh = series.filter { it.id !in taken }
        if (fresh.isEmpty()) return@transaction null
        val batch = newBatch(format, approved)
        conn.prepareStatement("INSERT INTO exported_series(series_id, batch_id, frozen) VALUES (?, ?, ?)").use { st ->
            for (s in fresh) {
                st.setLong(1, s.id); st.setLong(2, batch); st.setString(3, trainingJson.encodeToString(s)); st.executeUpdate()
            }
        }
        batch to fresh
    }

    /** Marks [ids] as exported before tracking began (nothing frozen); unknown or already exported ids are skipped. */
    @Synchronized
    fun recordLegacyExport(ids: Collection<Long>): Pair<Long, Int>? = transaction {
        val taken = exportedSeriesIds()
        val known = ids.distinct().filter { it !in taken && getSeries(it, includeDeleted = true) != null }
        if (known.isEmpty()) return@transaction null
        val batch = newBatch(ExportFormat.LEGACY, approved = false)
        conn.prepareStatement("INSERT INTO exported_series(series_id, batch_id) VALUES (?, ?)").use { st ->
            for (id in known) { st.setLong(1, id); st.setLong(2, batch); st.executeUpdate() }
        }
        batch to known.size
    }

    private fun newBatch(format: ExportFormat, approved: Boolean): Long =
        conn.prepareStatement("INSERT INTO export_batches(at, format, approved) VALUES (?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS).use {
            it.setString(1, auditTime.format(java.time.Instant.now()))
            it.setString(2, format.wire); it.setInt(3, if (approved) 1 else 0)
            it.executeUpdate()
            it.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
        }

    /** Every export batch, the newest first, with how many series it still holds. */
    @Synchronized
    fun exportBatches(): List<ExportBatch> = conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT b.id, b.at, b.format, b.approved, COUNT(e.series_id) AS n FROM export_batches b " +
                "LEFT JOIN exported_series e ON e.batch_id = b.id GROUP BY b.id ORDER BY b.id DESC"
        ).use { rs -> buildList { while (rs.next()) add(exportBatch(rs)) } }
    }

    @Synchronized
    fun exportBatch(id: Long): ExportBatch? = conn.prepareStatement(
        "SELECT b.id, b.at, b.format, b.approved, COUNT(e.series_id) AS n FROM export_batches b " +
            "LEFT JOIN exported_series e ON e.batch_id = b.id WHERE b.id = ? GROUP BY b.id"
    ).use { st -> st.setLong(1, id); st.executeQuery().use { rs -> if (rs.next()) exportBatch(rs) else null } }

    /** The series of batch [id] exactly as they went out, oldest id first (a legacy batch has none frozen). */
    @Synchronized
    fun frozenExport(id: Long): List<Series> = conn.prepareStatement(
        "SELECT frozen FROM exported_series WHERE batch_id = ? AND frozen IS NOT NULL ORDER BY series_id"
    ).use { st ->
        st.setLong(1, id)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(trainingJson.decodeFromString<Series>(rs.getString(1))) } }
    }

    private fun exportBatch(rs: java.sql.ResultSet) = ExportBatch(
        rs.getLong("id"), rs.getString("at"), ExportFormat.entries.first { it.wire == rs.getString("format") },
        rs.getInt("approved") != 0, rs.getInt("n"),
    )

    /** Approves or rejects [seriesId] for training; a later verdict replaces the earlier one. */
    @Synchronized
    fun setCuration(seriesId: Long, status: CurationStatus) {
        conn.prepareStatement(
            "INSERT INTO series_curation(series_id, status, at) VALUES (?, ?, ?) " +
                "ON CONFLICT(series_id) DO UPDATE SET status = excluded.status, at = excluded.at"
        ).use {
            it.setLong(1, seriesId); it.setString(2, status.wire); it.setString(3, auditTime.format(java.time.Instant.now())) // fixed width, so text order is time order
            it.executeUpdate()
        }
        execute("DELETE FROM series_curation_skipped WHERE series_id = ?", seriesId)
    }

    /** Skips [seriesId] in /admin/curate for good: its random pick never offers it again. */
    @Synchronized
    fun skipCuration(seriesId: Long) {
        conn.prepareStatement("INSERT OR IGNORE INTO series_curation_skipped(series_id, at) VALUES (?, ?)").use {
            it.setLong(1, seriesId); it.setString(2, auditTime.format(java.time.Instant.now())); it.executeUpdate()
        }
    }

    @Synchronized
    fun skippedCurationIds(): Set<Long> = conn.createStatement().use { st ->
        st.executeQuery("SELECT series_id FROM series_curation_skipped").use { rs -> buildSet { while (rs.next()) add(rs.getLong(1)) } }
    }

    @Synchronized
    fun curationOf(seriesId: Long): Curation? = conn.prepareStatement(
        "SELECT series_id, status, at FROM series_curation WHERE series_id = ?"
    ).use { st ->
        st.setLong(1, seriesId)
        st.executeQuery().use { rs -> if (rs.next()) curation(rs) else null }
    }

    /** Every verdict, the latest first (soft-deleted series included, flagged). */
    @Synchronized
    fun curationHistory(): List<Curation> = conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT c.series_id, c.status, c.at, s.deleted_at IS NOT NULL AS gone FROM series_curation c " +
                "JOIN series s ON s.id = c.series_id ORDER BY c.at DESC, c.series_id DESC"
        ).use { rs -> buildList { while (rs.next()) add(curation(rs).copy(deleted = rs.getInt("gone") != 0)) } }
    }

    /**
     * Stores [req] as the admin's training copy of [seriesId], replacing an earlier one; the user's series stays as it is.
     * The editor never re-sends the app's own soft-deleted holes (they are read-only there), so the user's are
     * merged in unless the copy already holds that detection: they are false positives for training all the same.
     */
    @Synchronized
    fun setTrainingCopy(seriesId: Long, req: SeriesRequest) {
        // Likewise the copy's own earlier removals: once stored deleted they show read-only and are not re-sent.
        val spots = req.holes.mapNotNull { it.spot() }.toMutableSet()
        val earlier = (trainingCopy(seriesId)?.holes.orEmpty() + holesOf(seriesId, includeDeleted = true)).filter { it.deleted }
        val kept = earlier.distinct().filter { h -> h.spot()?.let { spots.add(it) } ?: true }
        val stored = req.copy(holes = req.holes + kept, clientId = null)
        conn.prepareStatement(
            "INSERT INTO series_training(series_id, request, at) VALUES (?, ?, ?) " +
                "ON CONFLICT(series_id) DO UPDATE SET request = excluded.request, at = excluded.at"
        ).use {
            it.setLong(1, seriesId); it.setString(2, trainingJson.encodeToString(stored))
            it.setString(3, auditTime.format(java.time.Instant.now()))
            it.executeUpdate()
        }
    }

    /** Drops the training copy: the training data is the user's own version again. */
    @Synchronized
    fun clearTrainingCopy(seriesId: Long) = execute("DELETE FROM series_training WHERE series_id = ?", seriesId)

    @Synchronized
    fun trainingCopy(seriesId: Long): SeriesRequest? = conn.prepareStatement(
        "SELECT request FROM series_training WHERE series_id = ?"
    ).use { st ->
        st.setLong(1, seriesId)
        st.executeQuery().use { rs -> if (rs.next()) trainingJson.decodeFromString<SeriesRequest>(rs.getString(1)) else null }
    }

    /**
     * What training sees of [seriesId]: the user's series (photo, frame size, owner) with the admin's training copy's
     * holes, geometry, caliber and tag on top when there is one. Deleted holes come along, flagged, as in [getSeries].
     */
    @Synchronized
    fun trainingSeries(seriesId: Long): Series? {
        val series = getSeries(seriesId, includeDeleted = true) ?: return null
        val copy = trainingCopy(seriesId) ?: return series
        return series.copy(
            timestamp = copy.timestamp, caliber = copy.caliber.trim(), holes = copy.holes,
            geometry = copy.geometry, tag = copy.normalisedTag(),
        )
    }

    /**
     * Stores the admin's [corrected] ring and centre for [seriesId] in `ring_labels` only. The first save snapshots
     * the geometry the series had before anyone corrected it here ([firstGeometry]); a later save replaces only the
     * correction and its time, so the detector side never drifts. Clears a skip. Returns false for an unknown series
     * or one without a frame size (the correction's pixels would mean nothing).
     */
    @Synchronized
    fun setRingLabel(seriesId: Long, corrected: Geometry): Boolean = transaction {
        val series = getSeries(seriesId, includeDeleted = true) ?: return@transaction false
        val width = series.imageWidth ?: return@transaction false
        val height = series.imageHeight ?: return@transaction false
        val (detected, from) = firstGeometry(series)
        val now = auditTime.format(java.time.Instant.now())
        conn.prepareStatement(
            "INSERT INTO ring_labels(series_id, detected, detected_from, corrected, image_width, image_height, first_at, at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(series_id) DO UPDATE SET corrected = excluded.corrected, at = excluded.at"
        ).use {
            it.setLong(1, seriesId); it.setString(2, detected?.let { g -> trainingJson.encodeToString(g) }); it.setString(3, from)
            it.setString(4, trainingJson.encodeToString(corrected)); it.setInt(5, width); it.setInt(6, height)
            it.setString(7, now); it.setString(8, now)
            it.executeUpdate()
        }
        execute("DELETE FROM ring_labels_skipped WHERE series_id = ?", seriesId)
        true
    }

    /**
     * The geometry [series] had before any later edit, and where it came from: `created` (the app's first save, its
     * audit baseline), `baseline` (the oldest tracked state, for series older than the tracking) or `current` (no audit
     * row at all). The app scored with it; on the phone that is the detector's ring unless the user placed it by hand.
     */
    private fun firstGeometry(series: Series): Pair<Geometry?, String> {
        val oldest = conn.prepareStatement(
            "SELECT operation, after_state FROM series_audit WHERE series_id = ? AND baseline = 1 ORDER BY id LIMIT 1"
        ).use { st ->
            st.setLong(1, series.id)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
        } ?: return series.geometry to "current"
        val element = kotlinx.serialization.json.Json.parseToJsonElement(oldest.second)
            .let { (it as kotlinx.serialization.json.JsonObject)["geometry"] }
        val geometry = element?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.let { trainingJson.decodeFromJsonElement(Geometry.serializer(), it) }
        return geometry to (if (oldest.first == "created baseline") "created" else "baseline")
    }

    @Synchronized
    fun ringLabel(seriesId: Long): RingLabel? = conn.prepareStatement("$RING_LABEL_SELECT WHERE l.series_id = ?").use { st ->
        st.setLong(1, seriesId)
        st.executeQuery().use { rs -> if (rs.next()) ringLabel(rs) else null }
    }

    /** Every ring label, the latest save first (labels of soft-deleted series included, flagged). */
    @Synchronized
    fun ringLabels(): List<RingLabel> = conn.createStatement().use { st ->
        st.executeQuery("$RING_LABEL_SELECT ORDER BY l.at DESC, l.series_id DESC").use { rs ->
            buildList { while (rs.next()) add(ringLabel(rs)) }
        }
    }

    /** Live series with a frame size and no ring label yet, oldest first: what /admin/ring-labels picks from. */
    @Synchronized
    fun unlabelledRingSeriesIds(): List<Long> = conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT id FROM series WHERE deleted_at IS NULL AND image_width IS NOT NULL AND image_height IS NOT NULL " +
                "AND id NOT IN (SELECT series_id FROM ring_labels) ORDER BY id"
        ).use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
    }

    /** Skips [seriesId] in /admin/ring-labels for good. */
    @Synchronized
    fun skipRingLabel(seriesId: Long) {
        conn.prepareStatement("INSERT OR IGNORE INTO ring_labels_skipped(series_id, at) VALUES (?, ?)").use {
            it.setLong(1, seriesId); it.setString(2, auditTime.format(java.time.Instant.now())); it.executeUpdate()
        }
    }

    @Synchronized
    fun skippedRingLabelIds(): Set<Long> = conn.createStatement().use { st ->
        st.executeQuery("SELECT series_id FROM ring_labels_skipped").use { rs -> buildSet { while (rs.next()) add(rs.getLong(1)) } }
    }

    private fun ringLabel(rs: java.sql.ResultSet) = RingLabel(
        seriesId = rs.getLong("series_id"),
        imageWidth = rs.getInt("image_width"), imageHeight = rs.getInt("image_height"),
        detected = rs.getString("detected")?.let { trainingJson.decodeFromString<Geometry>(it) },
        detectedFrom = rs.getString("detected_from"),
        corrected = trainingJson.decodeFromString<Geometry>(rs.getString("corrected")),
        firstAt = rs.getString("first_at"), at = rs.getString("at"), deleted = rs.getInt("gone") != 0,
    )

    private fun curation(rs: java.sql.ResultSet) = Curation(
        rs.getLong("series_id"), CurationStatus.entries.first { it.wire == rs.getString("status") }, rs.getString("at"),
    )

    /**
     * A stored JPEG changes the series for the delta, so it bumps `updated_at` even when the upload carried
     * no size. [width]/[height] are the frame the app measured the hole coordinates in; null keeps the old ones.
     */
    @Synchronized
    fun imageStored(seriesId: Long, width: Int?, height: Int?) {
        // Half a size could not place a marker, so only a complete one is stored.
        val size = if (width == null || height == null) null else width to height
        conn.prepareStatement(
            "UPDATE series SET image_width = COALESCE(?, image_width), image_height = COALESCE(?, image_height), " +
                "updated_at = $NOW WHERE id = ?"
        ).use {
            it.setObject(1, size?.first); it.setObject(2, size?.second); it.setLong(3, seriesId); it.executeUpdate()
        }
    }

    @Synchronized
    fun seriesOwner(seriesId: Long, includeDeleted: Boolean = false): Long? {
        conn.prepareStatement("SELECT user_id FROM series WHERE id = ?${liveOnly(includeDeleted)}").use {
            it.setLong(1, seriesId)
            it.executeQuery().use { rs -> return if (rs.next()) rs.getLong(1) else null }
        }
    }

    /**
     * Newest id first — the order the `id < before` cursor pages in; the app sorts by timestamp itself.
     * [beforeId] is the last id of the previous page. [includeDeleted] (the admin page) lists soft-deleted series
     * too, as full rows flagged [Series.deleted]; [deletedHoles] (also the admin page) adds the soft-deleted holes,
     * flagged [Hole.deleted].
     */
    @Synchronized
    fun listSeries(
        userId: Long,
        limit: Int = Int.MAX_VALUE,
        beforeId: Long? = null,
        includeDeleted: Boolean = false,
        deletedHoles: Boolean = false,
    ): List<Series> {
        val series = mutableListOf<Series>()
        val before = if (beforeId == null) "" else " AND id < ?"
        conn.prepareStatement(
            "$SERIES_SELECT WHERE user_id = ?${liveOnly(includeDeleted)}$before ORDER BY id DESC LIMIT ?"
        ).use { st ->
            var i = 1
            st.setLong(i++, userId)
            if (beforeId != null) st.setLong(i++, beforeId)
            st.setInt(i, limit)
            st.executeQuery().use { rs -> while (rs.next()) series += seriesRow(rs, tombstone = false) }
        }
        return series.map { it.copy(holes = holesOf(it.id, includeDeleted = deletedHoles)) }
    }

    /**
     * The owner's series either side of [seriesId], soft-deleted ones included (they are rows of the admin user
     * page too): the next newer id and the next older id, null at either end.
     */
    @Synchronized
    fun adjacentSeries(userId: Long, seriesId: Long): Pair<Long?, Long?> {
        fun one(sql: String): Long? = conn.prepareStatement(sql).use { st ->
            st.setLong(1, userId)
            st.setLong(2, seriesId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1).takeUnless { rs.wasNull() } else null }
        }
        return one("SELECT MIN(id) FROM series WHERE user_id = ? AND id > ?") to
            one("SELECT MAX(id) FROM series WHERE user_id = ? AND id < ?")
    }

    /** Every tag the user has filed a live series under, alphabetical: the admin editor's suggestions. */
    @Synchronized
    fun tagsOf(userId: Long): List<String> {
        val tags = mutableListOf<String>()
        conn.prepareStatement(
            "SELECT DISTINCT tag FROM series WHERE user_id = ? AND tag IS NOT NULL${liveOnly(false)} ORDER BY tag"
        ).use { st ->
            st.setLong(1, userId)
            st.executeQuery().use { rs -> while (rs.next()) tags += rs.getString(1) }
        }
        return tags
    }

    /**
     * The sync delta: every series of the user touched at or after [since] (inclusive — the client dedupes
     * by id), deleted ones as tombstones. Unpaged; a client only ever asks for what changed since its last
     * sync. [since] is an `updated_at` the client got back from us, but an ISO instant is tolerated so a
     * stray `T`/`Z` cannot silently match nothing.
     */
    @Synchronized
    fun seriesSince(userId: Long, since: String): List<Series> {
        val series = mutableListOf<Series>()
        conn.prepareStatement("$SERIES_SELECT WHERE user_id = ? AND updated_at >= ? ORDER BY id").use { st ->
            st.setLong(1, userId)
            st.setString(2, since.trim().removeSuffix("Z").replace('T', ' '))
            st.executeQuery().use { rs -> while (rs.next()) series += seriesRow(rs) }
        }
        return series.map { if (it.deleted) it else it.copy(holes = holesOf(it.id)) }
    }

    /**
     * Soft delete: hidden from every user read and a tombstone in the delta, but the row, its holes and its image
     * stay (the admin page can restore it); only [deleteAccount] removes them.
     */
    @Synchronized
    fun deleteSeries(seriesId: Long, actor: AuditActor = AuditActor.Admin) = transaction {
        val before = stateOf(seriesId)
        execute("UPDATE series SET deleted_at = $NOW, updated_at = $NOW WHERE id = ? AND deleted_at IS NULL", seriesId)
        recordAudit(seriesId, actor, "deleted", before)
    }

    /** Undoes [deleteSeries] (the admin page). The new `updated_at` puts it back in the delta as a live series. */
    @Synchronized
    fun restoreSeries(seriesId: Long, actor: AuditActor = AuditActor.Admin) = transaction {
        val before = stateOf(seriesId)
        execute("UPDATE series SET deleted_at = NULL, updated_at = $NOW WHERE id = ? AND deleted_at IS NOT NULL", seriesId)
        recordAudit(seriesId, actor, "restored", before)
    }

    private fun liveOnly(includeDeleted: Boolean) = if (includeDeleted) "" else " AND deleted_at IS NULL"

    private fun fingerprint(id: Long): String? = conn.prepareStatement(
        "SELECT fingerprint FROM series_photo_baseline WHERE series_id = ?"
    ).use { it.setLong(1, id); it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }

    private fun stateOf(id: Long): String? = getSeries(id, includeDeleted = true)?.let { auditState(it, fingerprint(id)) }

    private fun recordAudit(id: Long, actor: AuditActor, operation: String, before: String?, baseline: Boolean = false) {
        val after = stateOf(id) ?: return
        if (!baseline && before == after) return
        val owner = seriesOwner(id, includeDeleted = true) ?: return
        require(actor == AuditActor.Admin || actor == AuditActor.app(owner)) { "invalid audit actor" }
        conn.prepareStatement("""INSERT INTO series_audit(series_id, owner_id, at, actor_type, actor_id,
            operation, baseline, before_state, after_state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""").use {
            it.setLong(1, id); it.setLong(2, owner); it.setString(3, auditTime.format(java.time.Instant.now()))
            it.setString(4, actor.type); it.setString(5, actor.id); it.setString(6, operation)
            it.setInt(7, if (baseline) 1 else 0); it.setString(8, before); it.setString(9, after); it.executeUpdate()
        }
        // Each row holds the whole before and after state: keep every baseline and the newest MAX_AUDIT_EDITS edits.
        conn.prepareStatement("""DELETE FROM series_audit WHERE series_id = ? AND baseline = 0 AND id NOT IN
            (SELECT id FROM series_audit WHERE series_id = ? AND baseline = 0 ORDER BY id DESC LIMIT $MAX_AUDIT_EDITS)""").use {
            it.setLong(1, id); it.setLong(2, id); it.executeUpdate()
        }
    }

    @Synchronized
    fun auditEvents(id: Long): List<AuditEvent> = conn.prepareStatement(
        "SELECT * FROM series_audit WHERE series_id = ? ORDER BY at DESC, id DESC"
    ).use { st -> st.setLong(1, id); st.executeQuery().use { rs -> buildList {
        while (rs.next()) add(AuditEvent(rs.getLong("id"), id, rs.getLong("owner_id"), rs.getString("at"),
            AuditActor(rs.getString("actor_type"), rs.getString("actor_id")), rs.getString("operation"),
            rs.getInt("baseline") != 0, rs.getString("before_state"), rs.getString("after_state")))
    } } }

    @Synchronized
    fun reviewPage(includeDeleted: Boolean, offset: Int): List<ReviewRow> {
        val ids = conn.prepareStatement("""SELECT s.id FROM series s
            LEFT JOIN (SELECT series_id, MAX(at) latest_at, MAX(id) latest_id FROM series_audit
                       WHERE baseline = 0 GROUP BY series_id) a ON a.series_id = s.id
            WHERE (${if (includeDeleted) "1" else "s.deleted_at IS NULL"}) AND
                (a.latest_id IS NOT NULL OR EXISTS (SELECT 1 FROM holes h WHERE h.series_id = s.id AND ($EDITED_HOLE_SQL)))
            ORDER BY a.latest_at DESC, a.latest_id DESC, s.id DESC LIMIT 51 OFFSET ?""").use { st ->
            st.setInt(1, offset.coerceAtLeast(0))
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }
        return ids.map { id -> ReviewRow(getSeries(id, includeDeleted = true)!!,
            getUser(seriesOwner(id, includeDeleted = true)!!)!!, auditEvents(id).filterNot { it.baseline }) }
    }

    /*
     * Photo files. The JPEG of series N is `N.jpg`. Replacing it writes `upload-*.tmp`, copies the old file to
     * `N.previous`, writes `N.journal` ("present"/"absent": was there an old file), and only then moves the
     * upload over `N.jpg` inside the DB transaction that records its fingerprint. A crash anywhere leaves a state
     * [recoverPhoto] resolves: the committed fingerprint decides which file wins.
     *
     * Locks: the file work runs under a per-series lock ([photoLock]) and outside the Db monitor, which every other
     * request shares (#24). The order is always photo lock first, then the monitor, never the reverse.
     */

    private val photoLocks = Array(64) { Any() }

    private inline fun <T> photoLock(id: Long, block: () -> T): T =
        synchronized(photoLocks[Math.floorMod(id, photoLocks.size)]) { block() }

    @Synchronized
    private fun committedFingerprint(id: Long): String? = fingerprint(id)

    /** Every series id, soft-deleted ones too: their photos stay until the account goes. */
    @Synchronized
    private fun allSeriesIds(): Set<Long> = conn.createStatement().use { st ->
        st.executeQuery("SELECT id FROM series").use { rs -> buildSet { while (rs.next()) add(rs.getLong(1)) } }
    }

    /**
     * Startup only, before requests are served: finish every interrupted replacement, then sweep files no series
     * owns any more (an account erasure whose delete failed, #23) and leftovers no journal needs. Only names this
     * server writes are touched: `<id>.jpg`, `<id>.previous`, `<id>.journal` and `upload-*.tmp`.
     */
    fun recoverPhotos(images: File) {
        images.listFiles()?.forEach { file ->
            val match = PHOTO_FILE.matchEntire(file.name)?.takeIf { it.groupValues[2] == "journal" } ?: return@forEach
            val id = match.groupValues[1].toLongOrNull() ?: return@forEach
            runCatching { recoverPhoto(images, id) }
                .onFailure { photoLog.error("photo recovery failed for series {}", id, it) }
        }
        sweepPhotos(images)
    }

    private fun sweepPhotos(images: File) {
        val ids = allSeriesIds()
        images.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name.startsWith("upload-") && file.name.endsWith(".tmp")) {
                deletePhotoFile(file, "abandoned upload")
                return@forEach
            }
            val match = PHOTO_FILE.matchEntire(file.name) ?: return@forEach
            val id = match.groupValues[1].toLongOrNull() ?: return@forEach
            when {
                // A wrong or empty database must not wipe the photo directory.
                id !in ids && ids.isNotEmpty() -> deletePhotoFile(file, "orphaned photo")
                match.groupValues[2] == "previous" && !File(images, "$id.journal").exists() ->
                    deletePhotoFile(file, "stale backup")
            }
        }
    }

    /**
     * Resolves one interrupted replacement, idempotently (#40): the restore comes first, then the journal goes,
     * then the backup, so a crash between any two steps leaves a state the next run resolves the same way.
     */
    private fun recoverPhoto(images: File, id: Long): Unit = photoLock(id) {
        val journal = File(images, "$id.journal")
        if (!journal.isFile) return@photoLock
        val target = imageFile(images, id)
        val backup = File(images, "$id.previous")
        if (seriesOwner(id, includeDeleted = true) == null) {
            deletePhotoFile(target, "photo of an erased series")
        } else {
            val committed = committedFingerprint(id)
            if (!target.isFile || committed == null || committed != photoHash(target.readBytes())) {
                if (journal.readText() == "present") {
                    if (backup.isFile) {
                        java.nio.file.Files.move(backup.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        // Only a restore already done (the move) removes the backup while the journal remains, so
                        // the file at the target is the old photo: keep it.
                        photoLog.warn("photo recovery for series {}: no backup, keeping the current file", id)
                    }
                } else java.nio.file.Files.deleteIfExists(target.toPath())
            }
        }
        java.nio.file.Files.deleteIfExists(journal.toPath())
        java.nio.file.Files.deleteIfExists(backup.toPath())
    }

    /**
     * Stores an upload. Blocking file I/O: call it off the request thread. Only the transaction and the final
     * rename hold the Db monitor; the write, the backup copy and the hashing hold just this series' lock, which
     * also keeps two uploads of one series from interleaving. [writeUpload] is a test seam.
     */
    fun storePhoto(
        images: File, id: Long, bytes: ByteArray, width: Int?, height: Int?, actor: AuditActor,
        writeUpload: (java.nio.file.Path, ByteArray) -> Unit = { path, data -> java.nio.file.Files.write(path, data) },
    ): Unit = photoLock(id) {
        val owner = seriesOwner(id) ?: error("Unknown series")
        require(actor == AuditActor.app(owner))
        val target = imageFile(images, id)
        val backup = File(images, "$id.previous")
        val journal = File(images, "$id.journal")
        // A recovery that failed earlier in this process; startup handles the ones from before it.
        if (journal.exists()) recoverPhoto(images, id)
        val newHash = photoHash(bytes)
        val tmp = java.nio.file.Files.createTempFile(images.toPath(), "upload-", ".tmp")
        try {
            writeUpload(tmp, bytes)
            val hadFile = target.isFile
            // Existing JPEGs without a fingerprint are historical baselines, never invented historical edits.
            val historicalHash = if (hadFile && committedFingerprint(id) == null) photoHash(target.readBytes()) else null
            if (hadFile) java.nio.file.Files.copy(target.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            journal.writeText(if (hadFile) "present" else "absent")
            commitPhoto(id, tmp, target, hadFile, historicalHash, newHash, width, height, actor)
        } catch (failure: Throwable) {
            // The DB transaction rolled back. Recovery either restores the old file or removes a new one.
            runCatching { recoverPhoto(images, id) }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            if (!journal.exists()) runCatching { java.nio.file.Files.deleteIfExists(backup.toPath()) }
            throw failure
        } finally {
            java.nio.file.Files.deleteIfExists(tmp)
        }
        // Committed: the journal goes first, so a crash here leaves only a backup the startup sweep removes.
        // Cleanup is recoverable; don't report a failed upload for cleanup alone.
        runCatching {
            java.nio.file.Files.deleteIfExists(journal.toPath())
            java.nio.file.Files.deleteIfExists(backup.toPath())
        }.onFailure { photoLog.warn("photo cleanup failed for series {}", id, it) }
    }

    @Synchronized
    private fun commitPhoto(
        id: Long, tmp: java.nio.file.Path, target: File, hadFile: Boolean, historicalHash: String?, newHash: String,
        width: Int?, height: Int?, actor: AuditActor,
    ) = transaction {
        // The account may have gone while the file was written: no row, no photo (#23).
        seriesOwner(id) ?: error("Unknown series")
        val previousHash = fingerprint(id)
        if (previousHash == null && hadFile) {
            setFingerprint(id, historicalHash ?: photoHash(target.readBytes()))
            recordAudit(id, actor, "historical photo baseline", null, baseline = true)
        }
        val before = stateOf(id)
        val first = previousHash == null && !hadFile
        java.nio.file.Files.move(tmp, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        imageStored(id, width, height)
        setFingerprint(id, newHash)
        recordAudit(id, actor, if (first) "photo baseline" else "photo updated", before, baseline = first)
    }

    /**
     * Account erasure's file half, after [deleteAccount] committed. Each series' files go under its photo lock, so
     * an upload in flight cannot put one back; a failed delete is logged and the next start sweeps it.
     */
    fun erasePhotos(images: File, ids: List<Long>) = ids.forEach { id ->
        photoLock(id) {
            listOf("jpg", "previous", "journal").forEach { deletePhotoFile(File(images, "$id.$it"), "erased photo") }
        }
    }

    private fun setFingerprint(id: Long, hash: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO series_photo_baseline(series_id, fingerprint) VALUES (?, ?)").use {
            it.setLong(1, id); it.setString(2, hash); it.executeUpdate()
        }
    }

    /** Drops the user, their sessions and every series; returns the deleted series ids so the caller can drop images. */
    @Synchronized
    fun deleteAccount(userId: Long): List<Long> = transaction {
        val ids = mutableListOf<Long>()
        conn.prepareStatement("SELECT id FROM series WHERE user_id = ?").use { st ->
            st.setLong(1, userId)
            st.executeQuery().use { rs -> while (rs.next()) ids += rs.getLong(1) }
        }
        execute("DELETE FROM holes WHERE series_id IN (SELECT id FROM series WHERE user_id = ?)", userId)
        execute("DELETE FROM series_audit WHERE owner_id = ?", userId)
        execute("DELETE FROM series_photo_baseline WHERE series_id IN (SELECT id FROM series WHERE user_id = ?)", userId)
        execute("DELETE FROM series_curation WHERE series_id IN (SELECT id FROM series WHERE user_id = ?)", userId)
        execute("DELETE FROM series_curation_skipped WHERE series_id IN (SELECT id FROM series WHERE user_id = ?)", userId)
        execute("DELETE FROM series_training WHERE series_id IN (SELECT id FROM series WHERE user_id = ?)", userId)
        execute("DELETE FROM exported_series WHERE series_id IN (SELECT id FROM series WHERE user_id = ?)", userId)
        execute("DELETE FROM ring_labels WHERE series_id IN (SELECT id FROM series WHERE user_id = ?)", userId)
        execute("DELETE FROM ring_labels_skipped WHERE series_id IN (SELECT id FROM series WHERE user_id = ?)", userId)
        execute("DELETE FROM series WHERE user_id = ?", userId)
        execute("DELETE FROM sessions WHERE user_id = ?", userId)
        // They may carry the user's e-mail address: gone with the account, like everything else of theirs.
        execute("DELETE FROM suggestions WHERE user_id = ?", userId)
        execute("DELETE FROM users WHERE id = ?", userId)
        ids
    }

    /**
     * True when [userId] has created [MAX_SERIES_PER_DAY] series in the last 24 h and [clientId] is not one
     * of them already (a retry of a stored series always gets its id back).
     */
    @Synchronized
    fun overDailyQuota(userId: Long, clientId: String?): Boolean {
        if (alreadyStored(userId, clientId)) return false
        conn.prepareStatement("SELECT COUNT(*) FROM series WHERE user_id = ? AND created_at >= datetime('now', '-1 day')").use {
            it.setLong(1, userId)
            it.executeQuery().use { rs -> rs.next(); return rs.getInt(1) >= MAX_SERIES_PER_DAY }
        }
    }

    /** Like [overDailyQuota], for every user together against [max]; a retry of a stored series still passes. */
    @Synchronized
    fun overGlobalDailyQuota(userId: Long, clientId: String?, max: Int): Boolean {
        if (alreadyStored(userId, clientId)) return false
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM series WHERE created_at >= datetime('now', '-1 day')")
                .use { rs -> rs.next(); return rs.getInt(1) >= max }
        }
    }

    private fun alreadyStored(userId: Long, clientId: String?): Boolean = clientId != null &&
        conn.prepareStatement("SELECT 1 FROM series WHERE user_id = ? AND client_id = ?").use {
            it.setLong(1, userId)
            it.setString(2, clientId)
            it.executeQuery().use { rs -> rs.next() }
        }

    /** @return the new suggestion's id. [req] is already normalised and validated. */
    @Synchronized
    fun insertSuggestion(userId: Long?, req: SuggestionRequest): Long =
        conn.prepareStatement(
            """INSERT INTO suggestions(user_id, title, description, email, platform, app_version, created_at)
               VALUES (?, ?, ?, ?, ?, ?, datetime('now'))""",
            Statement.RETURN_GENERATED_KEYS,
        ).use { st ->
            st.setObject(1, userId)
            st.setString(2, req.title)
            st.setString(3, req.description)
            st.setString(4, req.email)
            st.setString(5, req.platform)
            st.setString(6, req.appVersion)
            st.executeUpdate()
            st.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
        }

    @Synchronized
    fun suggestionMailed(id: Long) = execute("UPDATE suggestions SET mailed_at = datetime('now') WHERE id = ?", id)

    /** Suggestions from everyone in the last 24 h: what [MAX_SUGGESTIONS_PER_DAY] caps. */
    @Synchronized
    fun suggestionsLastDay(): Int = conn.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM suggestions WHERE created_at >= datetime('now', '-1 day')")
            .use { rs -> rs.next(); rs.getInt(1) }
    }

    @Synchronized
    fun getSuggestion(id: Long): SuggestionRow? = querySuggestions(id).firstOrNull()

    /** Newest first. */
    @Synchronized
    fun listSuggestions(): List<SuggestionRow> = querySuggestions(null)

    private fun querySuggestions(id: Long?): List<SuggestionRow> {
        val rows = mutableListOf<SuggestionRow>()
        conn.prepareStatement(
            """SELECT s.id, s.user_id, u.name, s.title, s.description, s.email, s.platform, s.app_version,
                      s.created_at, s.mailed_at
               FROM suggestions s LEFT JOIN users u ON u.id = s.user_id
               ${if (id == null) "" else "WHERE s.id = ?"}
               ORDER BY s.id DESC"""
        ).use { st ->
            if (id != null) st.setLong(1, id)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    rows += SuggestionRow(
                        rs.getLong(1), rs.getLong(2).takeIf { !rs.wasNull() }, rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10),
                    )
                }
            }
        }
        return rows
    }

    /** The database answers: what /health reports. */
    @Synchronized
    fun ping(): Boolean = runCatching {
        conn.createStatement().use { it.executeQuery("SELECT 1").use { rs -> rs.next() } }
    }.getOrDefault(false)

    private fun execute(sql: String, id: Long) =
        conn.prepareStatement(sql).use { it.setLong(1, id); it.executeUpdate() }

    private fun holesOf(seriesId: Long, includeDeleted: Boolean = false): List<Hole> {
        val holes = mutableListOf<Hole>()
        conn.prepareStatement(
            """SELECT x, y, ring, inner_ten, distance_mm, detected_ring, detected_inner_ten, detected_x, detected_y,
                      deleted, detected_w, detected_h, detected_conf
               FROM holes WHERE series_id = ? ${if (includeDeleted) "" else "AND deleted = 0 "}ORDER BY id"""
        ).use { st ->
            st.setLong(1, seriesId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    holes += Hole(
                        rs.doubleOrNull(1), rs.doubleOrNull(2), rs.getInt(3), rs.getInt(4) != 0, rs.doubleOrNull(5),
                        rs.intOrNull(6), rs.intOrNull(7)?.let { it != 0 }, rs.doubleOrNull(8), rs.doubleOrNull(9),
                        deleted = rs.getInt(10) != 0,
                        detectedW = rs.doubleOrNull(11), detectedH = rs.doubleOrNull(12),
                        detectedConf = rs.doubleOrNull(13),
                    )
                }
            }
        }
        return holes
    }

    override fun close() = conn.close()

    private companion object {
        val random = SecureRandom()

        val SERIES_SELECT =
            "SELECT id, timestamp, caliber, image_width, image_height, ${GEOMETRY_COLUMNS.joinToString(", ")}, " +
                "updated_at, deleted_at, tag FROM series"

        /**
         * A soft-deleted row is a [tombstone]: only the id and when it went, so the client can drop it. Otherwise
         * it is the full row flagged deleted; nothing writes to a deleted series, so its `updatedAt` is when it went.
         */
        fun seriesRow(rs: ResultSet, tombstone: Boolean = true): Series {
            val updatedAt = rs.getString(13)
            val deleted = rs.getString(14) != null
            if (deleted && tombstone) {
                return Series(rs.getLong(1), "", "", emptyList(), updatedAt = updatedAt, deleted = true)
            }
            return Series(
                id = rs.getLong(1),
                timestamp = rs.getString(2),
                caliber = rs.getString(3),
                holes = emptyList(),
                imageWidth = rs.intOrNull(4),
                imageHeight = rs.intOrNull(5),
                geometry = geometryRow(rs),
                updatedAt = updatedAt,
                deleted = deleted,
                tag = rs.getString(15),
            )
        }

        /** Columns 6..12 of [SERIES_SELECT]; any of them null means the series has no geometry. */
        fun geometryRow(rs: ResultSet): Geometry? {
            val g = (6..12).map { rs.doubleOrNull(it) ?: return null }
            return Geometry(g[0], g[1], g[2], g[3], g[4], g[5], g[6])
        }

        const val HOLE_COLUMNS =
            """id INTEGER PRIMARY KEY AUTOINCREMENT,
               series_id INTEGER NOT NULL REFERENCES series(id),
               x REAL,
               y REAL,
               ring INTEGER NOT NULL,
               inner_ten INTEGER NOT NULL,
               distance_mm REAL,
               detected_ring INTEGER,
               detected_inner_ten INTEGER,
               detected_x REAL,
               detected_y REAL,
               deleted INTEGER NOT NULL DEFAULT 0,
               detected_w REAL,
               detected_h REAL,
               detected_conf REAL"""
    }
}

/**
 * Now, as SQLite's UTC text (`datetime('now')`) plus milliseconds — the delta compares `updated_at` as a
 * string, and whole seconds cannot tell two writes in the same second apart.
 */
private const val NOW = "strftime('%Y-%m-%d %H:%M:%f', 'now')"

/** A session idle longer than this is expired; every use slides it. As a SQLite `datetime` modifier. */
private const val SESSION_IDLE = "-90 days"

/** Hex sha256: what `sessions.token` holds. */
internal fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/** The [Geometry] fields, in field order; every use below relies on that order. */
private val GEOMETRY_COLUMNS =
    listOf("centre_x", "centre_y", "ring_cx", "ring_cy", "ring_semi_major", "ring_semi_minor", "ring_rotation_rad")

/** Binds the seven geometry values (all null when there is none) starting at [from]. */
private fun PreparedStatement.setGeometry(from: Int, g: Geometry?) {
    val values = listOf(
        g?.centreX, g?.centreY, g?.ringCx, g?.ringCy, g?.ringSemiMajor, g?.ringSemiMinor, g?.ringRotationRad,
    )
    values.forEachIndexed { i, value -> setObject(from + i, value) }
}

private fun ResultSet.doubleOrNull(index: Int) = getDouble(index).takeIf { !wasNull() }

private fun ResultSet.intOrNull(index: Int) = getInt(index).takeIf { !wasNull() }

/** The admin's training-data verdict on a series. */
enum class CurationStatus(val wire: String) { APPROVED("approved"), REJECTED("rejected") }

data class Curation(val seriesId: Long, val status: CurationStatus, val at: String, val deleted: Boolean = false)

/** What a training export batch was written as; LEGACY = exported before tracking, nothing frozen to fetch again. */
enum class ExportFormat(val wire: String) { YOLO("yolo"), CVAT("cvat"), LEGACY("legacy") }

/** A training export batch and how many series it holds. */
data class ExportBatch(val id: Long, val at: String, val format: ExportFormat, val approved: Boolean, val count: Int)

/**
 * One hand-corrected ring and centre (/admin/ring-labels), in the pixels of the stored frame ([imageWidth] x
 * [imageHeight]). [detected] is what the series had before (null: no ring), [detectedFrom] where that came from
 * (`created`, `baseline` or `current`, see `Db.setRingLabel`). [firstAt] is the first save, [at] the latest.
 */
@kotlinx.serialization.Serializable
data class RingLabel(
    val seriesId: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val detected: Geometry?,
    val detectedFrom: String,
    val corrected: Geometry,
    val firstAt: String,
    val at: String,
    val deleted: Boolean = false,
)

private const val RING_LABEL_SELECT =
    "SELECT l.series_id, l.detected, l.detected_from, l.corrected, l.image_width, l.image_height, l.first_at, l.at, " +
        "s.deleted_at IS NOT NULL AS gone FROM ring_labels l JOIN series s ON s.id = l.series_id"
