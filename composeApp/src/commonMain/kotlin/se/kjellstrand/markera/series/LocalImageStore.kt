package se.kjellstrand.markera.series

import kotlinx.io.files.Path

/** Durable JPEGs in app files, separate from the evictable signed-in image cache. */
class LocalImageStore(filesDir: Path) : ImageCache by FileImageCache(Path(filesDir, "local-series"))

/** Old callers keep compiling; local saves must supply a durable store in #654. */
internal object UnconfiguredLocalImageStore : ImageCache {
    override fun read(id: Long): ByteArray? = TODO("#654")
    override fun write(id: Long, bytes: ByteArray): Unit = TODO("#654")
    override fun readThumb(id: Long): ByteArray? = TODO("#654")
    override fun writeThumb(id: Long, bytes: ByteArray): Unit = TODO("#654")
    override fun delete(id: Long): Unit = TODO("#654")
    override fun clear(): Unit = TODO("#654")
}
