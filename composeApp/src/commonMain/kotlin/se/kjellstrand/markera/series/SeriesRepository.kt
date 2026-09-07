package se.kjellstrand.markera.series

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import se.kjellstrand.markera.series.db.MarkeraDb
import se.kjellstrand.markera.webshooter.api.webshooterJson

/** The scanned frames on disk, one JPEG per series id. */
interface ImageCache {
    fun read(id: Long): ByteArray?
    fun write(id: Long, bytes: ByteArray)
    fun delete(id: Long)
    fun clear()
}

/**
 * The local copy of the user's series (PLAN task 57b): every screen reads
 * [series] from SQLite and never pages the backend, and [refresh] pulls only
 * what changed since the stored `updatedAt` stamp (tombstones included).
 *
 * Writes go to the server first and to the cache after, so a failed write
 * leaves nothing behind; a failed *read* (offline) keeps the cached rows and is
 * reported, never thrown.
 */
class SeriesRepository(
    private val api: SeriesApi,
    db: MarkeraDb,
    private val images: ImageCache,
    private val session: BackendSessionRepository,
) {
    private val q = db.seriesQueries

    private val _series = MutableStateFlow<List<SeriesDto>>(emptyList())

    /** Newest timestamp first. Empty until the first [refresh] reads the cache. */
    val series: StateFlow<List<SeriesDto>> = _series.asStateFlow()

    /**
     * Publishes the cached rows, then merges the server's delta into them.
     * @return null when the server part succeeded, else what went wrong — the
     * cached rows stay published either way.
     */
    suspend fun refresh(): Throwable? = withContext(Dispatchers.Default) {
        val user = session.auth.value?.userId?.toString() ?: return@withContext null
        val stamp = q.lastSync().executeAsOneOrNull()
        // A different account on this device: its rows and images are not ours.
        if (stamp != null && stamp.user_id != user) wipe()
        val since = stamp?.takeIf { it.user_id == user }?.last_sync
        reload()
        try {
            if (since == null) fullLoad(user) else applyDelta(api.listSeriesSince(since), user)
            reload()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e
        }
    }

    /**
     * POSTs a scanned series and caches it at once, so History shows it without
     * a round trip. The row carries no `updatedAt` yet — the next [refresh]
     * delta returns it and fills that in.
     */
    suspend fun save(req: SeriesRequest): Long {
        val id = api.postSeries(req)
        withContext(Dispatchers.Default) {
            insert(
                SeriesDto(
                    id = id,
                    timestamp = req.timestamp,
                    caliber = req.caliber,
                    holes = req.holes,
                    geometry = req.geometry,
                ),
            )
            reload()
        }
        return id
    }

    /** The scanned frame for [id]: uploaded, then cached so it is never downloaded back. */
    suspend fun uploadImage(id: Long, bytes: ByteArray, width: Int, height: Int) {
        api.postSeriesImage(id, bytes, width, height)
        withContext(Dispatchers.Default) {
            images.write(id, bytes)
            _series.value.firstOrNull { it.id == id }?.let {
                insert(it.copy(hasImage = true, imageWidth = width, imageHeight = height))
                reload()
            }
        }
    }

    suspend fun update(id: Long, req: SeriesRequest) {
        api.updateSeries(id, req)
        withContext(Dispatchers.Default) {
            val cached = _series.value.firstOrNull { it.id == id }
            insert(
                cached?.copy(
                    timestamp = req.timestamp,
                    caliber = req.caliber,
                    holes = req.holes,
                    geometry = req.geometry,
                ) ?: SeriesDto(id, req.timestamp, req.caliber, req.holes, geometry = req.geometry),
            )
            reload()
        }
    }

    suspend fun delete(id: Long) {
        api.deleteSeries(id)
        withContext(Dispatchers.Default) {
            q.deleteById(id)
            images.delete(id)
            reload()
        }
    }

    /** The stored JPEG: the cached file, else downloaded once and kept. Null if it can't be had. */
    suspend fun image(id: Long): ByteArray? {
        withContext(Dispatchers.Default) { images.read(id) }?.let { return it }
        return try {
            api.getSeriesImage(id).also { withContext(Dispatchers.Default) { images.write(id, it) } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Sign-out, account deletion, or a different user: nothing cached survives. */
    suspend fun clear() = withContext(Dispatchers.Default) {
        wipe()
        reload()
    }

    private suspend fun fullLoad(user: String) {
        val all = mutableListOf<SeriesDto>()
        var before: Long? = null
        do {
            val page = api.listSeries(before = before)
            all += page
            before = nextPageCursor(page)
        } while (before != null)
        q.transaction {
            q.deleteAllSeries()
            all.forEach { insert(it) }
        }
        stamp(all, user)
    }

    private fun applyDelta(changed: List<SeriesDto>, user: String) {
        q.transaction {
            changed.forEach { if (it.deleted) q.deleteById(it.id) else insert(it) }
        }
        changed.filter { it.deleted }.forEach { images.delete(it.id) }
        stamp(changed, user)
    }

    /** The newest stamp seen is what the next delta asks from; an empty answer changes nothing. */
    private fun stamp(seen: List<SeriesDto>, user: String) {
        seen.mapNotNull { it.updatedAt }.maxOrNull()?.let { q.setLastSync(user, it) }
    }

    private fun insert(dto: SeriesDto) = q.upsert(
        id = dto.id,
        timestamp = dto.timestamp,
        caliber = dto.caliber,
        updated_at = dto.updatedAt ?: "",
        has_image = if (dto.hasImage) 1L else 0L,
        json = webshooterJson.encodeToString(dto),
    )

    private fun reload() {
        _series.value = q.selectAll().executeAsList()
            .map { webshooterJson.decodeFromString<SeriesDto>(it) }
    }

    private fun wipe() {
        q.deleteAllSeries()
        q.clearSync()
        images.clear()
    }
}
