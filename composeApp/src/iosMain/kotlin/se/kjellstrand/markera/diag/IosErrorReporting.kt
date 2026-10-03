package se.kjellstrand.markera.diag

import kotlin.experimental.ExperimentalNativeApi

/**
 * What the Swift host implements with the Sentry Cocoa SDK (`SentryReporter.swift`). Strings only:
 * a Kotlin Throwable means nothing to Sentry, so the type, message and stack trace cross as text.
 */
interface NativeErrorReporter {
    fun breadcrumb(category: String, message: String)
    fun report(place: String, type: String, message: String, stackTrace: String, details: Map<String, String>)
}

/** Called once by the host, after it has started Sentry: routes [ErrorLog] to [reporter]. */
@OptIn(ExperimentalNativeApi::class)
@Suppress("unused")
fun installErrorReporter(reporter: NativeErrorReporter) {
    ErrorLog.sink = object : ErrorSink {
        override fun breadcrumb(category: String, message: String) = reporter.breadcrumb(category, message)

        override fun report(where: String, error: Throwable, details: Map<String, String>) = reporter.report(
            place = where,
            type = error::class.simpleName ?: "Throwable",
            message = error.message ?: error.toString(),
            stackTrace = error.stackTraceToString(),
            details = details,
        )
    }
    // An uncaught Kotlin exception aborts the process, and Sentry's crash handler then sees only
    // the abort. Leave the Kotlin trace as the last breadcrumb so the crash report carries it.
    setUnhandledExceptionHook { error ->
        reporter.breadcrumb("crash", error.stackTraceToString().take(3000))
        terminateWithUnhandledException(error)
    }
}
