package se.kjellstrand.markera.series

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*

/** Excel only reads the CSVs as UTF-8 if they start with a BOM. */
private fun csvBytes(csv: String): ByteArray = "\uFEFF$csv".toByteArray()

/**
 * Everything the app has, as one zip in `cacheDir/export`: `series.csv`,
 * `holes.csv` and `images/<id>.jpg` for every series whose frame the cache can
 * hand over (an image that neither cache nor server gives is skipped silently).
 * Only the newest export is kept — the older ones go before it is written.
 */
suspend fun exportSeriesZip(context: Context, repository: SeriesRepository): File =
    withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val file = File(dir, "markera-export-$stamp.zip")
        val series = repository.series.value
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("series.csv"))
            zip.write(csvBytes(seriesCsv(series)))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("holes.csv"))
            zip.write(csvBytes(holesCsv(series)))
            zip.closeEntry()
            for (s in series) {
                if (!s.hasImage) continue
                val bytes = repository.image(s.id) ?: continue
                zip.putNextEntry(ZipEntry("images/${s.id}.jpg"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        file
    }

/** Hands [file] to the system chooser through the app's FileProvider. */
suspend fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, getString(Res.string.history_export)),
    )
}
