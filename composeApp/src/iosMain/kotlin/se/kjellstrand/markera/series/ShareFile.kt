package se.kjellstrand.markera.series

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.popoverPresentationController

/**
 * The system share sheet for a file on disk (the series export zip).
 * Fire-and-forget: it returns as soon as the sheet is up.
 */
suspend fun shareFile(path: String) = withContext(Dispatchers.Main) {
    val root = keyWindow().rootViewController ?: return@withContext
    val sheet = UIActivityViewController(
        activityItems = listOf(NSURL.fileURLWithPath(path)),
        applicationActivities = null,
    )
    // On iPad the sheet is a popover and traps without an anchor.
    sheet.popoverPresentationController?.sourceView = root.view
    root.presentViewController(sheet, animated = true, completion = null)
}
