package se.kjellstrand.markera.series

import kotlinx.io.Sink
import kotlinx.io.writeIntLe
import kotlinx.io.writeShortLe

/**
 * A store-only (method 0) zip writer, so the export needs no deflate — and no
 * JVM-only zip API, which `commonMain` cannot reach. JPEGs don't compress and
 * the two CSVs are tiny, so the lost ratio is not worth a compressor.
 *
 * No zip64 (an export past 4 GB would need it) and no directory entries.
 */
class ZipWriter(private val sink: Sink) {

    private class Entry(val name: ByteArray, val crc: Int, val size: Int, val offset: Int)

    private val entries = mutableListOf<Entry>()
    private var offset = 0

    /** A zip's 16-bit fields are unsigned; only the low two bytes ever matter here. */
    private fun u16(value: Int) = sink.writeShortLe(value.toShort())

    /** Adds one stored file; [name] uses `/` separators and is written as UTF-8. */
    fun entry(name: String, bytes: ByteArray) {
        val raw = name.encodeToByteArray()
        val crc = crc32(bytes)
        entries += Entry(raw, crc, bytes.size, offset)
        sink.writeIntLe(0x04034b50)
        u16(20)          // version needed
        u16(0x0800)      // flags: bit 11 = UTF-8 names
        u16(0)           // method: stored
        u16(0)           // DOS time
        u16(0)           // DOS date
        sink.writeIntLe(crc)
        sink.writeIntLe(bytes.size)  // compressed
        sink.writeIntLe(bytes.size)  // uncompressed
        u16(raw.size)
        u16(0)           // extra
        sink.write(raw)
        sink.write(bytes)
        offset += 30 + raw.size + bytes.size
    }

    /** Writes the central directory and end record. Call once, then close the sink. */
    fun finish() {
        val start = offset
        entries.forEach { e ->
            sink.writeIntLe(0x02014b50)
            u16(20)      // version made by
            u16(20)      // version needed
            u16(0x0800)
            u16(0)       // method
            u16(0)       // DOS time
            u16(0)       // DOS date
            sink.writeIntLe(e.crc)
            sink.writeIntLe(e.size)
            sink.writeIntLe(e.size)
            u16(e.name.size)
            u16(0)       // extra
            u16(0)       // comment
            u16(0)       // disk
            u16(0)       // internal attrs
            sink.writeIntLe(0)   // external attrs
            sink.writeIntLe(e.offset)
            sink.write(e.name)
            offset += 46 + e.name.size
        }
        sink.writeIntLe(0x06054b50)
        u16(0)           // disk
        u16(0)           // disk with the central directory
        u16(entries.size)
        u16(entries.size)
        sink.writeIntLe(offset - start)
        sink.writeIntLe(start)
        u16(0)           // comment
    }
}

private val CRC_TABLE = IntArray(256) { i ->
    var c = i
    repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
    c
}

/** Standard CRC-32 (polynomial 0xEDB88320), as the zip headers want it. */
fun crc32(bytes: ByteArray): Int {
    var c = -1
    for (b in bytes) c = (c ushr 8) xor CRC_TABLE[(c xor b.toInt()) and 0xFF]
    return c.inv()
}
