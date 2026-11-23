package se.kjellstrand.markera.series

import kotlinx.io.files.Path

/**
 * Forces written bytes to the storage device. kotlinx-io's `flush()` only empties its own
 * buffer into the OS, so after a power cut a renamed file can come back empty or missing.
 */
interface FileSync {
    /** fsync of a regular file's data. Throws when the file cannot be synced. */
    fun syncFile(path: Path)

    /** fsync of a directory, which makes a rename in it durable. Best effort: never throws,
     * since not every platform can open a directory (Windows, where the JVM unit tests run). */
    fun syncDirectory(path: Path)
}

/** The platform's [FileSync]: `FileDescriptor.sync()` on Android, `F_FULLFSYNC`/`fsync` on iOS. */
internal expect val PlatformFileSync: FileSync
