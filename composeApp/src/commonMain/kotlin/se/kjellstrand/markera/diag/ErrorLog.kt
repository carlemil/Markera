package se.kjellstrand.markera.diag

import kotlin.concurrent.Volatile

/** Where [ErrorLog] sends things: Sentry on Android, nothing yet on iOS. */
interface ErrorSink {
    fun breadcrumb(category: String, message: String)
    fun report(where: String, error: Throwable, details: Map<String, String>)
}

/**
 * Remote error log. A [report] (or a crash) carries the last hundred or so [breadcrumb]s
 * along, and on Android each breadcrumb is also sent as a Sentry log right away, error or
 * not; so leave a trail through any flow that can fail on somebody else's phone. Without a
 * [sink] both just print.
 *
 * Never pass tokens, e-mail addresses or names: this leaves the device.
 */
object ErrorLog {
    @Volatile
    var sink: ErrorSink? = null

    /** A step worth seeing in the trail of a later report, e.g. `breadcrumb("signin", "google: sheet shown")`. */
    fun breadcrumb(category: String, message: String) {
        println("Markera: [$category] $message")
        sink?.breadcrumb(category, message)
    }

    /** A handled failure worth looking at. [where] becomes a searchable tag. */
    fun report(where: String, error: Throwable, details: Map<String, String> = emptyMap()) {
        println("Markera: [$where] $error $details")
        sink?.report(where, error, details)
    }
}
