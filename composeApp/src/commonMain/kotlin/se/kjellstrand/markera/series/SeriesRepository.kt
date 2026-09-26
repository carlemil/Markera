package se.kjellstrand.markera.series

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import se.kjellstrand.markera.series.db.MarkeraDb

/** The scanned frames on disk, one JPEG per series id, plus its list thumbnail. */
interface ImageCache {
    fun read(id: Long): ByteArray?
    fun write(id: Long, bytes: ByteArray)
    fun readThumb(id: Long): ByteArray?
    fun writeThumb(id: Long, bytes: ByteArray)
    /** Removes both the frame and its thumbnail. */
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
 * reported, never thrown. Refreshes and writes take turns ([lock]), so a save
 * can never land in the middle of a full load that then wipes it.
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

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The server answered 401: signed out and wiped already, the UI only has to say so. */
    val sessionExpired: SharedFlow<Unit> = _sessionExpired

    private val lock = Mutex()

    /** Held while a [refresh] runs, so one asked for meanwhile joins it instead of starting another. */
    private val refreshing = Mutex()

    /** What the last finished [refresh] returned, for the callers that joined it. */
    private var lastRefresh: Throwable? = null

    /**
     * Publishes the cached rows, then merges the server's delta into them.
     * @return null when the server part succeeded, else what went wrong — the
     * cached rows stay published either way. Asked for while one runs, it waits
     * for that one and returns its outcome (a screen opened during the start-up
     * full load keeps its spinner until the rows are really there).
     */
    suspend fun refresh(): Throwable? {
        if (!refreshing.tryLock()) {
            refreshing.withLock { }
            return lastRefresh
        }
        return try {
            withContext(Dispatchers.Default) { lock.withLock { refreshLocked() } }.also { lastRefresh = it }
        } finally {
            refreshing.unlock()
        }
    }

    private suspend fun refreshLocked(): Throwable? {
        val user = session.auth.value?.userId?.toString() ?: return null
        val stamp = q.lastSync().executeAsOneOrNull()
        // A different account on this device: its rows and images are not ours.
        if (stamp != null && stamp.user_id != user) wipe()
        val since = stamp?.takeIf { it.user_id == user }?.last_sync
        reload()
        try {
            // First, so what it sends comes back in the load below like any other series.
            drainOutbox(user)
            if (since == null) fullLoad(user) else applyDelta(api.listSeriesSince(since), user)
            reload()
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is SeriesApiException && e.isUnauthorized) expire()
            return e
        }
    }

    /** One write at a time, and never inside a refresh; a 401 signs out on the way. */
    private suspend fun <T> locked(block: suspend () -> T): T = lock.withLock {
        try {
            block()
        } catch (e: SeriesApiException) {
            if (e.isUnauthorized) expire()
            throw e
        }
    }

    private suspend fun expire() {
        session.signOut()
        withContext(Dispatchers.Default) {
            wipe()
            reload()
        }
        _sessionExpired.tryEmit(Unit)
    }

    /**
     * POSTs a scanned series and caches it at once, so History shows it without
     * a round trip. The row carries no `updatedAt` yet — the next [refresh]
     * delta returns it and fills that in.
     */
    suspend fun save(req: SeriesRequest): Long = locked {
        val id = api.postSeries(req)
        withContext(Dispatchers.Default) {
            insert(
                SeriesDto(
                    id = id,
                    timestamp = req.timestamp,
                    caliber = req.caliber,
                    holes = req.holes,
                    geometry = req.geometry,
                    tag = req.tag,
                ),
            )
            reload()
        }
        id
    }

    /**
     * Keeps a committed series the server did not take (offline, 5xx) for the next [refresh] to send,
     * so the next scan can never be what loses it. [seriesId] set means the POST went through and only
     * [image] is left to upload. Signed out, there is no one to keep it for.
     */
    suspend fun enqueue(req: SeriesRequest, image: EncodedImage?, seriesId: Long? = null) {
        val user = session.auth.value?.userId?.toString() ?: return
        withContext(Dispatchers.Default) {
            q.enqueue(user, seriesJson.encodeToString(req), seriesId, image?.bytes, image?.width?.toLong(), image?.height?.toLong())
        }
    }

    /**
     * Sends [user]'s outbox in order. A network failure, 5xx or 429 stops it (the rest waits for the next
     * refresh); a 4xx other than 401 can never succeed, so that row is dropped rather than block the rest.
     */
    private suspend fun drainOutbox(user: String) {
        for (row in q.outboxFor(user).executeAsList()) {
            try {
                val id = row.series_id ?: api.postSeries(seriesJson.decodeFromString<SeriesRequest>(row.request))
                    .also { q.outboxPosted(it, row.id) }
                val bytes = row.image
                if (bytes != null && row.image_width != null && row.image_height != null) {
                    api.postSeriesImage(id, bytes, row.image_width.toInt(), row.image_height.toInt())
                    images.write(id, bytes)
                }
            } catch (e: SeriesApiException) {
                // Over today's quota: the rest waits, but the refresh itself still goes through.
                if (e.status == 429) return
                if (e.isUnauthorized || e.isRetryable) throw e
                println("Markera: outbox row ${row.id} refused (${e.status}), dropped")
            }
            q.outboxDone(row.id)
        }
    }

    /** The scanned frame for [id]: uploaded, then cached so it is never downloaded back. */
    suspend fun uploadImage(id: Long, bytes: ByteArray, width: Int, height: Int) = locked {
        api.postSeriesImage(id, bytes, width, height)
        withContext(Dispatchers.Default) {
            images.write(id, bytes)
            _series.value.firstOrNull { it.id == id }?.let {
                insert(it.copy(hasImage = true, imageWidth = width, imageHeight = height))
                reload()
            }
        }
    }

    suspend fun update(id: Long, req: SeriesRequest) = locked {
        api.updateSeries(id, req)
        // Deleted holes go up for training and never come back down.
        val kept = req.holes.filterNot { it.deleted }
        withContext(Dispatchers.Default) {
            val cached = _series.value.firstOrNull { it.id == id }
            insert(
                cached?.copy(
                    timestamp = req.timestamp,
                    caliber = req.caliber,
                    holes = kept,
                    geometry = req.geometry,
                    tag = req.tag,
                ) ?: SeriesDto(id, req.timestamp, req.caliber, kept, geometry = req.geometry, tag = req.tag),
            )
            reload()
        }
    }

    suspend fun delete(id: Long) = locked {
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

    /** The list thumbnail JPEG: the cached one, else cut from [image] once and kept. */
    suspend fun thumbnail(id: Long, maxDim: Int): ByteArray? {
        withContext(Dispatchers.Default) { images.readThumb(id) }?.let { return it }
        val full = image(id) ?: return null
        return withContext(Dispatchers.Default) {
            thumbnailJpeg(full, maxDim)?.also { images.writeThumb(id, it) }
        }
    }

    /** Sign-out, account deletion, or a different user: nothing cached survives. */
    suspend fun clear() = locked {
        withContext(Dispatchers.Default) {
            wipe()
            reload()
        }
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
        json = seriesJson.encodeToString(dto),
    )

    /** Never throws: an undecodable row is dropped, and the stamp with it so the next refresh reloads it whole. */
    private fun reload() {
        val rows = q.selectAll().executeAsList()
        val good = rows.mapNotNull { runCatching { seriesJson.decodeFromString<SeriesDto>(it.json) }.getOrNull() }
        if (good.size < rows.size) {
            val kept = good.mapTo(HashSet()) { it.id }
            rows.filter { it.id !in kept }.forEach { q.deleteById(it.id) }
            q.clearSync()
        }
        _series.value = good
    }

    private fun wipe() {
        q.deleteAllSeries()
        q.clearSync()
        images.clear()
    }
}
