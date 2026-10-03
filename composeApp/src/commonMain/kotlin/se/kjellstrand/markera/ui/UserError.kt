package se.kjellstrand.markera.ui

import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource
import se.kjellstrand.markera.diag.ErrorLog
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.SeriesApiException
import se.kjellstrand.markera.series.SignInCancelledException

/**
 * What to tell the user about [this]; the details go to [ErrorLog]. Null = say nothing (the user cancelled).
 * Being offline is not worth a report, so an IOException only leaves a breadcrumb.
 */
fun Throwable.userMessage(): StringResource? {
    if (this is CancellationException || this is SignInCancelledException) return null
    if (this is kotlinx.io.IOException) {
        ErrorLog.breadcrumb("error", "network: $this")
    } else {
        ErrorLog.report(
            "toast",
            this,
            if (this is SeriesApiException) mapOf("status" to status.toString()) else emptyMap(),
        )
    }
    return when {
        this is SeriesApiException && isUnauthorized -> Res.string.error_session_expired
        this is SeriesApiException -> Res.string.error_server
        // Ktor timeouts and Darwin errors are IOExceptions too.
        this is kotlinx.io.IOException -> Res.string.error_network
        else -> Res.string.error_generic
    }
}
