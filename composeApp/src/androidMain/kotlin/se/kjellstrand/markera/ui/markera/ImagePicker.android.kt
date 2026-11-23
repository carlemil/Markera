package se.kjellstrand.markera.ui.markera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.kjellstrand.markera.diag.ErrorLog
import se.kjellstrand.markera.series.exifInstant

@Composable
actual fun rememberImagePicker(onPicked: (List<PickedImage>) -> Unit): (ImageSource) -> Unit {
    val context = LocalContext.current.applicationContext
    val callback = rememberUpdatedState(onPicked)
    val deliver: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) callback.value(uris.map { uri -> PickedImage { loadUri(context, uri) } })
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), deliver)
    // Any DocumentsProvider: Drive, Downloads, an SD card, a USB stick.
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), deliver)
    return { source ->
        when (source) {
            ImageSource.GALLERY ->
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            ImageSource.FILES -> files.launch(arrayOf("image/*"))
        }
    }
}

/**
 * Decodes [uri] off the main thread at the largest power-of-two subsample that keeps
 * the shorter side (the square the scan crops) at least [IMPORT_SQUARE_SIDE], then
 * scales it to exactly that (never below) and turns it upright by its EXIF orientation.
 */
private suspend fun loadUri(context: Context, uri: Uri): LoadedImage? = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val shorter = minOf(bounds.outWidth, bounds.outHeight)
        if (shorter <= 0) return@withContext null
        val sample = importSampleSize(shorter)
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@withContext null
        val exif = resolver.openInputStream(uri)?.use { ExifInterface(it) }
        val rotation = exif?.rotationDegrees ?: 0
        // The subsample leaves the short side anywhere in [target, 2 * target); scale and turn
        // it in one copy, and let the bigger decode go as soon as that copy exists.
        val (width, height) = importScaledSize(decoded.width, decoded.height)
        val upright = if (rotation == 0 && width == decoded.width && height == decoded.height) decoded else {
            val matrix = Matrix().apply {
                setScale(width.toFloat() / decoded.width, height.toFloat() / decoded.height)
                postRotate(rotation.toFloat())
            }
            val copy = try {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            } catch (e: Throwable) {
                decoded.recycle()
                throw e
            }
            // createBitmap may hand back the source itself; only a real copy frees it.
            copy.also { if (it !== decoded) decoded.recycle() }
        }
        LoadedImage(
            upright,
            exifInstant(
                exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                exif?.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A provider gone offline or a file that is not really an image: that one is skipped.
        ErrorLog.report("import-decode", e)
        null
    } catch (e: OutOfMemoryError) {
        ErrorLog.report("import-decode", e)
        null
    }
}
