package se.kjellstrand.markera.series

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import org.jetbrains.compose.resources.getString
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*

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
