package se.kjellstrand.markera.series

import se.kjellstrand.markera.diag.ErrorLog
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
import kotlinx.io.IOException
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
 * The local copy of the user's series: every screen reads
 * [series] from SQLite and never pages the backend, and [refresh] pulls only
 * what changed since the stored `updatedAt` stamp (tombstones included).
 *
 * Committed scans and photos are durable locally before HTTP. A failed read
 * keeps cached rows, and local work survives sign-out and account switches. Refreshes and writes take turns ([lock]), so a save
 * can never land in the middle of a full load that then wipes it.
 */
class SeriesRepository(
    private val api: SeriesApi,
    db: MarkeraDb,
    private val images: ImageCache,
    private val session: BackendSessionRepository,
    private val localImages: ImageCache,
) : ClaimGate {
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    private fun newClientId() = kotlin.uuid.Uuid.random().toString()
    /** SQLite commits only after the durable JPEG has been written. An interrupted write
     * can leave an orphan file, but never a committed row pointing at a partial JPEG. */
    suspend fun keepLocal(request: SeriesRequest, image: EncodedImage?, owner: Long? = null): Long = locked {
        keepLocalLocked(request, image, owner).also { reload() }
    }

    private fun keepLocalLocked(request: SeriesRequest, image: EncodedImage?, owner: Long?): Long {
        val req = if (request.clientId == null) request.copy(clientId = newClientId()) else request
        var id = 0L
        q.transaction {
            q.insertLocal(req.clientId!!, owner?.toString(), seriesJson.encodeToString(req),
                image?.width?.toLong(), image?.height?.toLong(), 0, req.timestamp)
            val row = q.localByClient(req.clientId).executeAsOne()
            id = row.local_id
            if (image != null && row.has_image == 0L) {
                localImages.write(id, image.bytes)
                q.localPhoto(image.width.toLong(), image.height.toLong(), id)
            }
        }
        return id
    }

    suspend fun send(localId: Long): Long = locked { sendLocked(localId) }
    /** A row gone since keepLocal was sent (and deleted) by a refresh draining meanwhile. */
    internal suspend fun acknowledged(localId: Long): Boolean = locked {
        q.localById(localId).executeAsOneOrNull()?.let { it.server_id != null } ?: true
    }
    suspend fun retry(localId: Long) { send(localId) }
    /** Detail may show the acknowledged server id while its photo is still refused. */
    suspend fun retrySeries(id: Long) = locked {
        val localId = if (id < 0) -id else q.selectLocal().executeAsList().first {
            it.server_id == id && it.owner_user_id == session.auth.value?.userId?.toString()
        }.local_id
        sendLocked(localId)
    }
    suspend fun deleteLocal(localId: Long) = locked { deleteLocalLocked(localId); reload() }

    private fun deleteLocalLocked(id: Long) {
        // A failed file removal leaves the row available for another Delete attempt.
        localImages.delete(id)
        q.removeLocal(id)
    }

    /** Transfer the entire offer before publishing auth. Process death after publication
     * therefore resumes this user's queue without asking for the same decision again. */
    /** Rows a sign-in as [userId] offers: unowned or another account's, but never one that
     * account's server already acknowledged (only its photo pending): that one waits for it. */
    private fun offeredLocked(userId: Long) = q.selectLocal().executeAsList()
        .filter { it.owner_user_id != userId.toString() && (it.server_id == null || it.owner_user_id == null) }

    suspend fun claimLocal(userId: Long) = locked {
        adoptLegacyLocked()
        val offered = offeredLocked(userId)
        q.transaction { q.claimOffered(userId.toString(), userId.toString()) }
        // Add is an explicit fresh upload to the new account. A refusal already
        // retried for the previous owner must not suppress that choice.
        refusedRetried.removeAll(offered.map { it.local_id }.toSet())
        reload()
    }
    suspend fun localCount(userId: Long): Int = locked {
        adoptLegacyLocked()
        offeredLocked(userId).size
    }
    suspend fun deleteOfferedLocal(userId: Long) = locked {
        adoptLegacyLocked()
        offeredLocked(userId).forEach { deleteLocalLocked(it.local_id) }
        reload()
    }
    suspend fun adoptLegacyOutbox() = locked { adoptLegacyLocked(); reload() }
    override suspend fun needsDecision(auth: BackendAuth): Int = locked {
        adoptLegacyLocked()
        val count = offeredLocked(auth.userId).size
        // A held login is signed out. Do not expose the previous account's read cache
        // behind the dialog or after Stay/dismiss, even if sign-in began while signed in.
        if (count > 0) wipe()
        reload()
        count
    }

    private fun adoptLegacyLocked() {
        for (row in q.allOutbox().executeAsList()) {
            q.transaction {
                // Older outbox rows may predate clientId. The server de-duplicates per user,
                // so a per-device row id could collide with another phone's: use a fresh one.
                // The row and outboxDone commit together, so a crash re-adopts from scratch.
                val old = seriesJson.decodeFromString<SeriesRequest>(row.request)
                val req = if (old.clientId == null) old.copy(clientId = newClientId()) else old
                // The old drain skipped a photo without dimensions; 0x0 would only be refused.
                val photo = row.image?.let { bytes ->
                    val w = row.image_width?.toInt() ?: return@let null
                    val h = row.image_height?.toInt() ?: return@let null
                    EncodedImage(bytes, w, h)
                }
                val id = keepLocalLocked(req, photo, row.user_id.toLong())
                if (row.series_id != null) q.localPosted(row.series_id, id)
                q.outboxDone(row.id)
            }
        }
    }

    private val refusedRetried = mutableSetOf<Long>()

    private suspend fun sendLocked(localId: Long): Long {
        val row = q.localById(localId).executeAsOneOrNull() ?: error("Local series no longer exists")
        check(row.owner_user_id != null && row.owner_user_id == session.auth.value?.userId?.toString()) {
            "Local series belongs to another account"
        }
        val req = seriesJson.decodeFromString<SeriesRequest>(row.request)
        try {
            val id = row.server_id ?: api.postSeries(req).also { serverId ->
                q.transaction {
                    insert(dto(serverId, req, row.has_image != 0L, row.image_width, row.image_height))
                    q.localPosted(serverId, localId)
                }
                reload()
            }
            // After a crash/switch the acknowledged row may no longer be in the read cache.
            if (_series.value.none { it.id == id }) insert(dto(id, req, row.has_image != 0L, row.image_width, row.image_height))
            if (row.has_image != 0L) {
                val bytes = localImages.read(localId) ?: error("Local photo unavailable")
                api.postSeriesImage(id, bytes, row.image_width!!.toInt(), row.image_height!!.toInt())
                images.write(id, bytes)
            }
            deleteLocalLocked(localId)
            reload()
            return id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = (e as? SeriesApiException)?.status
            val refused = status != null && status in 400..499 && status != 401 && status != 429
            q.localFailure(if (refused) "refused" else "queued", status?.toLong(),
                if (refused) 1 else row.reported, localId)
            if (refused && row.reported == 0L) ErrorLog.report("local-series-refused", e,
                mapOf("status" to status.toString()))
            reload()
            throw e
        }
    }

    private fun dto(id: Long, req: SeriesRequest, hasImage: Boolean, width: Long?, height: Long?) = SeriesDto(
        id, req.timestamp, req.caliber, req.holes.filterNot { it.deleted }, hasImage = hasImage,
        imageWidth = width?.toInt(), imageHeight = height?.toInt(), geometry = req.geometry, tag = req.tag,
    )

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
        adoptLegacyLocked()
        val user = session.auth.value?.userId?.toString() ?: run { reload(); return null }
        val stamp = q.lastSync().executeAsOneOrNull()
        // A different account on this device: its rows and images are not ours.
        if (stamp != null && stamp.user_id != user) wipe()
        val since = stamp?.takeIf { it.user_id == user }?.last_sync?.takeIf { it.isNotEmpty() }
        reload()
        try {
            // First, so what it sends comes back in the load below like any other series.
            drainLocal(user)
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

    private suspend fun drainLocal(user: String) {
        for (row in q.selectLocal().executeAsList().filter { it.owner_user_id == user }) {
            if (row.state == "refused" && !refusedRetried.add(row.local_id)) continue
            try {
                sendLocked(row.local_id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SeriesApiException) {
                if (e.status == 429) return
                if (e.isUnauthorized || e.isRetryable) throw e
                // A refused row stays visible but does not block the following rows.
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                // A local defect (e.g. a missing photo file) must not stop the delta below
                // or the rows after it; the row stays queued for an explicit retry.
                ErrorLog.report("local-series-send", e)
            }
        }
    }

    /** One write at a time, and never inside a refresh; a 401 signs out on the way. */
    private suspend fun <T> locked(block: suspend () -> T): T = lock.withLock {
        try {
            // SQLite and multi-MB JPEG file I/O: never on the caller's (often Main) thread.
            withContext(Dispatchers.Default) { block() }
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

    /** Compatibility convenience for metadata-only callers; the same durable-first path. */
    suspend fun save(req: SeriesRequest): Long = locked {
        val owner = session.auth.value?.userId
        val id = keepLocalLocked(req, null, owner)
        reload()
        if (owner == null) -id else sendLocked(id)
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
        if (id < 0) {
            val row = q.localById(-id).executeAsOne()
            val original = seriesJson.decodeFromString<SeriesRequest>(row.request)
            val stillRemoved = original.holes.filter { old -> old.deleted && req.holes.none { next ->
                !next.deleted && old.detectedX != null && old.detectedY != null &&
                    next.detectedX == old.detectedX && next.detectedY == old.detectedY
            } }
            q.editLocal(seriesJson.encodeToString(req.copy(clientId = original.clientId,
                holes = (req.holes + stillRemoved).distinct())), -id)
            reload()
            return@locked
        }
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
        if (id < 0) {
            deleteLocalLocked(-id)
            reload()
            return@locked
        }
        api.deleteSeries(id)
        // An acknowledged row can still own a refused/pending photo. Deleting the
        // series must cancel that upload too, rather than retrying a deleted id.
        q.selectLocal().executeAsList().filter {
            it.server_id == id && it.owner_user_id == session.auth.value?.userId?.toString()
        }.forEach { deleteLocalLocked(it.local_id) }
        withContext(Dispatchers.Default) {
            q.deleteById(id)
            images.delete(id)
            reload()
        }
    }

    /** The stored JPEG: the cached file, else downloaded once and kept. Null if it can't be had. */
    suspend fun image(id: Long): ByteArray? {
        if (id < 0) return withContext(Dispatchers.Default) { localImages.read(-id) }
        withContext(Dispatchers.Default) {
            q.selectLocal().executeAsList().firstOrNull {
                it.server_id == id && it.owner_user_id == session.auth.value?.userId?.toString()
            }?.let { localImages.read(it.local_id) }
        }?.let { return it }
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
        val store = if (id < 0) localImages else images
        val key = if (id < 0) -id else id
        withContext(Dispatchers.Default) { store.readThumb(key) }?.let { return it }
        val full = image(id) ?: return null
        return withContext(Dispatchers.Default) {
            thumbnailJpeg(full, maxDim)?.also { store.writeThumb(key, it) }
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
        val newest = seen.mapNotNull { it.updatedAt }.maxOrNull()
        if (newest != null) q.setLastSync(user, newest)
        else if (q.lastSync().executeAsOneOrNull() == null) q.setLastSync(user, "")
    }

    private fun insert(dto: SeriesDto) {
        val owner = session.auth.value?.userId?.toString()
        if (owner != null && q.lastSync().executeAsOneOrNull() == null) q.setLastSync(owner, "")
        q.upsert(id = dto.id, timestamp = dto.timestamp, json = seriesJson.encodeToString(dto))
    }

    /** Never throws: an undecodable row is dropped, and the stamp with it so the next refresh reloads it whole. */
    private fun reload() {
        val rows = q.selectAll().executeAsList()
        val good = rows.mapNotNull { runCatching { seriesJson.decodeFromString<SeriesDto>(it.json) }.getOrNull() }
        if (good.size < rows.size) {
            val kept = good.mapTo(HashSet()) { it.id }
            rows.filter { it.id !in kept }.forEach { q.deleteById(it.id) }
            q.clearSync()
        }
        val local = q.selectLocal().executeAsList().filter { it.server_id == null }.mapNotNull { row ->
            // Kept on disk (it may decode in a later version); only left out of the list.
            val req = runCatching { seriesJson.decodeFromString<SeriesRequest>(row.request) }.getOrNull()
                ?: return@mapNotNull null
            dto(-row.local_id, req, row.has_image != 0L, row.image_width, row.image_height).copy(local = when {
                row.owner_user_id == null -> LocalMark.UNCLAIMED
                row.state == "refused" -> LocalMark.REFUSED
                else -> LocalMark.QUEUED
            })
        }
        val pendingPhotos = q.selectLocal().executeAsList().filter {
            it.server_id != null &&
                it.owner_user_id == session.auth.value?.userId?.toString()
        }.associateBy { it.server_id }
        _series.value = (good.map {
            val pendingPhoto = pendingPhotos[it.id]
            if (pendingPhoto != null) it.copy(
                hasImage = it.hasImage || pendingPhoto.has_image != 0L,
                imageWidth = it.imageWidth ?: pendingPhoto.image_width?.toInt(),
                imageHeight = it.imageHeight ?: pendingPhoto.image_height?.toInt(),
                local = if (pendingPhoto.state == "refused") LocalMark.REFUSED else null,
            ) else it
        } + local).sortedByDescending { it.timestamp }
    }

    private fun wipe() {
        q.deleteAllSeries()
        q.clearSync()
        images.clear()
    }
}
