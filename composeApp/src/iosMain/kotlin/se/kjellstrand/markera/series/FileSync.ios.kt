package se.kjellstrand.markera.series

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fsync
import platform.posix.open

/** Darwin's `F_FULLFSYNC` (fcntl.h): plain fsync on Apple platforms stops at the drive's cache. */
private const val F_FULLFSYNC = 51

@OptIn(ExperimentalForeignApi::class)
internal actual val PlatformFileSync: FileSync = object : FileSync {
    override fun syncFile(path: Path) {
        if (!sync(path)) throw IOException("fsync failed for ${path.name} (errno $errno)")
    }

    override fun syncDirectory(path: Path) {
        sync(path)
    }

    /** Opens read-only (enough for fsync on Darwin, and the only way to open a directory). */
    private fun sync(path: Path): Boolean {
        val fd = open(path.toString(), O_RDONLY)
        if (fd < 0) return false
        try {
            // F_FULLFSYNC can be unsupported by the file system; fsync is then the best there is.
            return fcntl(fd, F_FULLFSYNC) != -1 || fsync(fd) == 0
        } finally {
            close(fd)
        }
    }
}
