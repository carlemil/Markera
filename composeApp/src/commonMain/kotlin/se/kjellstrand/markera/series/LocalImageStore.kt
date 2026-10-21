package se.kjellstrand.markera.series

import kotlinx.io.files.Path

/** Durable JPEGs in app files, separate from the evictable signed-in image cache. */
class LocalImageStore(filesDir: Path) : ImageCache by FileImageCache(Path(filesDir, "local-series"), durable = true)
