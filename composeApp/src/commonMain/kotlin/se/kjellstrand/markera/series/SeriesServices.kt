package se.kjellstrand.markera.series

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.engine.HttpClientEngine
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import se.kjellstrand.markera.series.db.MarkeraDb

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
        val client = createSeriesHttpClient(engine)
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

/** The cached series JPEGs under `cacheDir/series/`: `<id>.jpg` and its thumbnail `<id>.thumb.jpg`. */
class FileImageCache(private val dir: Path) : ImageCache {

    private fun file(id: Long) = Path(dir, "$id.jpg")
    private fun thumb(id: Long) = Path(dir, "$id.thumb.jpg")

    override fun read(id: Long) = read(file(id))
    override fun write(id: Long, bytes: ByteArray) = write(file(id), bytes)
    override fun readThumb(id: Long) = read(thumb(id))
    override fun writeThumb(id: Long, bytes: ByteArray) = write(thumb(id), bytes)

    private fun read(path: Path): ByteArray? = runCatching {
        SystemFileSystem.source(path).buffered().use { it.readByteArray() }
    }.getOrNull()

    private fun write(path: Path, bytes: ByteArray) {
        runCatching {
            SystemFileSystem.createDirectories(dir)
            SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
        }
    }

    override fun delete(id: Long) {
        runCatching { SystemFileSystem.delete(file(id), mustExist = false) }
        runCatching { SystemFileSystem.delete(thumb(id), mustExist = false) }
    }

    override fun clear() {
        // kotlinx-io has no recursive delete; the dir only ever holds flat JPEGs.
        runCatching { SystemFileSystem.list(dir) }.getOrNull()?.forEach {
            runCatching { SystemFileSystem.delete(it, mustExist = false) }
        }
    }
}
