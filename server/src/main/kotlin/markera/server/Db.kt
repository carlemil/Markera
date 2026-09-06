package markera.server

import java.io.File
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** A user plus how many series they have; only the admin pages need it. */
data class UserRow(val id: Long, val provider: String, val subject: String, val createdAt: String, val seriesCount: Int)

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
                     UNIQUE(provider, subject))"""
            )
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
                     created_at TEXT NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS holes (
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     series_id INTEGER NOT NULL REFERENCES series(id),
                     x REAL NOT NULL,
                     y REAL NOT NULL,
                     ring INTEGER NOT NULL,
                     inner_ten INTEGER NOT NULL,
                     distance_mm REAL NOT NULL)"""
            )
        }
    }

    @Synchronized
    fun upsertUser(provider: String, subject: String): Long {
        conn.prepareStatement(
            "INSERT OR IGNORE INTO users(provider, subject, created_at) VALUES (?, ?, datetime('now'))"
        ).use { it.setString(1, provider); it.setString(2, subject); it.executeUpdate() }
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
            "INSERT INTO holes(series_id, x, y, ring, inner_ten, distance_mm) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { st ->
            for (h in holes) {
                st.setLong(1, seriesId)
                st.setDouble(2, h.x)
                st.setDouble(3, h.y)
                st.setInt(4, h.ring)
                st.setInt(5, if (h.innerTen) 1 else 0)
                st.setDouble(6, h.distanceMm)
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
            """SELECT u.id, u.provider, u.subject, u.created_at, COUNT(s.id)
               FROM users u LEFT JOIN series s ON s.user_id = u.id $filter
               GROUP BY u.id ORDER BY u.id DESC"""
        ).use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    users += UserRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5))
                }
            }
        }
        return users
    }

    @Synchronized
    fun getSeries(seriesId: Long): Series? {
        conn.prepareStatement("SELECT id, timestamp, caliber FROM series WHERE id = ?").use { st ->
            st.setLong(1, seriesId)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                return Series(rs.getLong(1), rs.getString(2), rs.getString(3), holesOf(seriesId))
            }
        }
    }

    @Synchronized
    fun seriesOwner(seriesId: Long): Long? {
        conn.prepareStatement("SELECT user_id FROM series WHERE id = ?").use {
            it.setLong(1, seriesId)
            it.executeQuery().use { rs -> return if (rs.next()) rs.getLong(1) else null }
        }
    }

    @Synchronized
    fun listSeries(userId: Long): List<Series> {
        val series = mutableListOf<Series>()
        conn.prepareStatement(
            "SELECT id, timestamp, caliber FROM series WHERE user_id = ? ORDER BY timestamp DESC, id DESC"
        ).use { st ->
            st.setLong(1, userId)
            st.executeQuery().use { rs ->
                while (rs.next()) series += Series(rs.getLong(1), rs.getString(2), rs.getString(3), emptyList())
            }
        }
        return series.map { it.copy(holes = holesOf(it.id)) }
    }

    private fun holesOf(seriesId: Long): List<Hole> {
        val holes = mutableListOf<Hole>()
        conn.prepareStatement(
            "SELECT x, y, ring, inner_ten, distance_mm FROM holes WHERE series_id = ? ORDER BY id"
        ).use { st ->
            st.setLong(1, seriesId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    holes += Hole(rs.getDouble(1), rs.getDouble(2), rs.getInt(3), rs.getInt(4) != 0, rs.getDouble(5))
                }
            }
        }
        return holes
    }

    override fun close() = conn.close()

    private companion object {
        val random = SecureRandom()
    }
}
