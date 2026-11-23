package se.kjellstrand.markera.series

import android.system.Os
import android.system.OsConstants
import java.io.RandomAccessFile
import kotlinx.io.files.Path

internal actual val PlatformFileSync: FileSync = object : FileSync {
    override fun syncFile(path: Path) {
        // "rw" because fsync needs a descriptor open for writing on some file systems.
        RandomAccessFile(path.toString(), "rw").use { it.fd.sync() }
    }

    override fun syncDirectory(path: Path) {
        // java.io cannot open a directory; Os can (Linux). In JVM unit tests Os is a stub.
        runCatching {
            val fd = Os.open(path.toString(), OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
    }
}
