package se.kjellstrand.markera.series

import se.kjellstrand.markera.series.db.MarkeraDb

/** A fresh in-memory SQLite cache; the driver is the platform's. */
expect fun testSeriesDb(): MarkeraDb

/** An [ImageCache] in a map, so a test can see what was stored. */
class FakeImageCache : ImageCache {
    val files = mutableMapOf<Long, ByteArray>()
    override fun read(id: Long): ByteArray? = files[id]
    override fun write(id: Long, bytes: ByteArray) { files[id] = bytes }
    override fun delete(id: Long) { files.remove(id) }
    override fun clear() = files.clear()
}
