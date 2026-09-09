package se.kjellstrand.markera.series

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.engine.HttpClientEngine
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import se.kjellstrand.markera.series.db.MarkeraDb
import se.kjellstrand.markera.webshooter.api.createWebshooterHttpClient

/**
 * Wires the Markera series backend stack (HTTP client → API → session → local
 * cache). One instance for the app; the platform entry point builds it and
 * passes it down.
 */
class SeriesServices(
    engine: HttpClientEngine,
    baseUrl: String,
    val store: BackendTokenStore,
    driver: SqlDriver,
    val cacheDir: Path,
) {
    val session: BackendSessionRepository
    val api: SeriesApi
    val repository: SeriesRepository

    init {
        val client = createWebshooterHttpClient(engine)
        lateinit var repo: BackendSessionRepository
        api = SeriesApi(client, baseUrl, tokenProvider = { repo.currentToken })
        repo = BackendSessionRepository(api, store)
        session = repo
        repository = SeriesRepository(
            api = api,
            db = MarkeraDb(driver),
            images = FileImageCache(Path(cacheDir, "series")),
            session = repo,
        )
    }
}

/** The cached series JPEGs, one file per id under `cacheDir/series/`. */
class FileImageCache(private val dir: Path) : ImageCache {

    private fun file(id: Long) = Path(dir, "$id.jpg")

    override fun read(id: Long): ByteArray? = runCatching {
        SystemFileSystem.source(file(id)).buffered().use { it.readByteArray() }
    }.getOrNull()

    override fun write(id: Long, bytes: ByteArray) {
        runCatching {
            SystemFileSystem.createDirectories(dir)
            SystemFileSystem.sink(file(id)).buffered().use { it.write(bytes) }
        }
    }

    override fun delete(id: Long) {
        runCatching { SystemFileSystem.delete(file(id), mustExist = false) }
    }

    override fun clear() {
        // kotlinx-io has no recursive delete; the dir only ever holds flat JPEGs.
        runCatching { SystemFileSystem.list(dir) }.getOrNull()?.forEach {
            runCatching { SystemFileSystem.delete(it, mustExist = false) }
        }
    }
}
