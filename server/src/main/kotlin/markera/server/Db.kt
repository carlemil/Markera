package markera.server

import java.io.File
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

/** A user plus how many series they have; only the admin pages need it. */
data class UserRow(
    val id: Long,
    val provider: String,
    val subject: String,
    val name: String?,
    val createdAt: String,
    val seriesCount: Int,
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
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS users (
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     provider TEXT NOT NULL,
                     subject TEXT NOT NULL,
                     created_at TEXT NOT NULL,
                     name TEXT,
                     UNIQUE(provider, subject))"""
            )
            // Databases created before names existed.
            val columns = st.executeQuery("PRAGMA table_info(users)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
            if ("name" !in columns) st.executeUpdate("ALTER TABLE users ADD COLUMN name TEXT")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS sessions (
                     token TEXT PRIMARY KEY,
                     user_id INTEGER NOT NULL REFERENCES users(id),
                     created_at TEXT NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS series (
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     user_id INTEGER NOT NULL REFERENCES users(id),
                     timestamp TEXT NOT NULL,
                     caliber TEXT NOT NULL,
                     created_at TEXT NOT NULL,
                     image_width INTEGER,
                     image_height INTEGER)"""
            )
            // Databases created before the image carried the frame size.
            val seriesColumns = st.executeQuery("PRAGMA table_info(series)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
            if ("image_width" !in seriesColumns) {
                st.executeUpdate("ALTER TABLE series ADD COLUMN image_width INTEGER")
                st.executeUpdate("ALTER TABLE series ADD COLUMN image_height INTEGER")
            }
            st.executeUpdate("CREATE TABLE IF NOT EXISTS holes ($HOLE_COLUMNS)")
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
        }
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

    @Synchronized
    fun createSession(userId: Long): String {
        val token = ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        conn.prepareStatement("INSERT INTO sessions(token, user_id, created_at) VALUES (?, ?, datetime('now'))")
            .use { it.setString(1, token); it.setLong(2, userId); it.executeUpdate() }
        return token
    }

    @Synchronized
    fun userForToken(token: String): Long? {
        conn.prepareStatement("SELECT user_id FROM sessions WHERE token = ?").use {
            it.setString(1, token)
            it.executeQuery().use { rs -> return if (rs.next()) rs.getLong(1) else null }
        }
    }

    @Synchronized
    fun insertSeries(userId: Long, timestamp: String, caliber: String, holes: List<Hole>): Long {
        val seriesId: Long
        conn.prepareStatement(
            "INSERT INTO series(user_id, timestamp, caliber, created_at) VALUES (?, ?, ?, datetime('now'))",
            Statement.RETURN_GENERATED_KEYS
        ).use {
            it.setLong(1, userId)
            it.setString(2, timestamp)
            it.setString(3, caliber)
            it.executeUpdate()
            it.generatedKeys.use { rs -> rs.next(); seriesId = rs.getLong(1) }
        }
        conn.prepareStatement(
            """INSERT INTO holes(series_id, x, y, ring, inner_ten, distance_mm, detected_ring, detected_inner_ten)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)"""
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
                st.addBatch()
            }
            st.executeBatch()
        }
        return seriesId
    }

    @Synchronized
    fun listUsers(): List<UserRow> = queryUsers("")

    @Synchronized
    fun getUser(userId: Long): UserRow? = queryUsers("WHERE u.id = $userId").firstOrNull()

    /** [filter] is built from Longs only — never interpolate anything a client can control. */
    private fun queryUsers(filter: String): List<UserRow> {
        val users = mutableListOf<UserRow>()
        conn.prepareStatement(
            """SELECT u.id, u.provider, u.subject, u.name, u.created_at, COUNT(s.id)
               FROM users u LEFT JOIN series s ON s.user_id = u.id $filter
               GROUP BY u.id ORDER BY u.id DESC"""
        ).use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    users += UserRow(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)
                    )
                }
            }
        }
        return users
    }

    @Synchronized
    fun getSeries(seriesId: Long): Series? {
        conn.prepareStatement("$SERIES_SELECT WHERE id = ?").use { st ->
            st.setLong(1, seriesId)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                return seriesRow(rs).copy(holes = holesOf(seriesId))
            }
        }
    }

    /** The size of the frame the app measured the hole coordinates in; sent with the image upload. */
    @Synchronized
    fun setImageSize(seriesId: Long, width: Int, height: Int) {
        conn.prepareStatement("UPDATE series SET image_width = ?, image_height = ? WHERE id = ?").use {
            it.setInt(1, width); it.setInt(2, height); it.setLong(3, seriesId); it.executeUpdate()
        }
    }

    @Synchronized
    fun seriesOwner(seriesId: Long): Long? {
        conn.prepareStatement("SELECT user_id FROM series WHERE id = ?").use {
            it.setLong(1, seriesId)
            it.executeQuery().use { rs -> return if (rs.next()) rs.getLong(1) else null }
        }
    }

    /** Newest first. [beforeId] pages by id (monotonic with insertion), which is what the app holds from the last page. */
    @Synchronized
    fun listSeries(userId: Long, limit: Int = Int.MAX_VALUE, beforeId: Long? = null): List<Series> {
        val series = mutableListOf<Series>()
        val before = if (beforeId == null) "" else " AND id < $beforeId" // a Long, never client text
        conn.prepareStatement("$SERIES_SELECT WHERE user_id = ?$before ORDER BY timestamp DESC, id DESC LIMIT ?")
            .use { st ->
                st.setLong(1, userId)
                st.setInt(2, limit)
                st.executeQuery().use { rs -> while (rs.next()) series += seriesRow(rs) }
            }
        return series.map { it.copy(holes = holesOf(it.id)) }
    }

    @Synchronized
    fun deleteSeries(seriesId: Long) {
        execute("DELETE FROM holes WHERE series_id = ?", seriesId)
        execute("DELETE FROM series WHERE id = ?", seriesId)
    }

    /** Drops the user, their sessions and every series; returns the deleted series ids so the caller can drop images. */
    @Synchronized
    fun deleteAccount(userId: Long): List<Long> {
        val ids = mutableListOf<Long>()
        conn.prepareStatement("SELECT id FROM series WHERE user_id = ?").use { st ->
            st.setLong(1, userId)
            st.executeQuery().use { rs -> while (rs.next()) ids += rs.getLong(1) }
        }
        execute("DELETE FROM holes WHERE series_id IN (SELECT id FROM series WHERE user_id = ?)", userId)
        execute("DELETE FROM series WHERE user_id = ?", userId)
        execute("DELETE FROM sessions WHERE user_id = ?", userId)
        execute("DELETE FROM users WHERE id = ?", userId)
        return ids
    }

    private fun execute(sql: String, id: Long) =
        conn.prepareStatement(sql).use { it.setLong(1, id); it.executeUpdate() }

    private fun holesOf(seriesId: Long): List<Hole> {
        val holes = mutableListOf<Hole>()
        conn.prepareStatement(
            """SELECT x, y, ring, inner_ten, distance_mm, detected_ring, detected_inner_ten
               FROM holes WHERE series_id = ? ORDER BY id"""
        ).use { st ->
            st.setLong(1, seriesId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    holes += Hole(
                        rs.doubleOrNull(1), rs.doubleOrNull(2), rs.getInt(3), rs.getInt(4) != 0, rs.doubleOrNull(5),
                        rs.intOrNull(6), rs.intOrNull(7)?.let { it != 0 },
                    )
                }
            }
        }
        return holes
    }

    override fun close() = conn.close()

    private companion object {
        val random = SecureRandom()

        const val SERIES_SELECT = "SELECT id, timestamp, caliber, image_width, image_height FROM series"

        fun seriesRow(rs: ResultSet) = Series(
            id = rs.getLong(1),
            timestamp = rs.getString(2),
            caliber = rs.getString(3),
            holes = emptyList(),
            imageWidth = rs.intOrNull(4),
            imageHeight = rs.intOrNull(5),
        )

        const val HOLE_COLUMNS =
            """id INTEGER PRIMARY KEY AUTOINCREMENT,
               series_id INTEGER NOT NULL REFERENCES series(id),
               x REAL,
               y REAL,
               ring INTEGER NOT NULL,
               inner_ten INTEGER NOT NULL,
               distance_mm REAL,
               detected_ring INTEGER,
               detected_inner_ten INTEGER"""
    }
}

private fun ResultSet.doubleOrNull(index: Int) = getDouble(index).takeIf { !wasNull() }

private fun ResultSet.intOrNull(index: Int) = getInt(index).takeIf { !wasNull() }
