package se.kjellstrand.markera.ui

import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.SeriesApiException
import se.kjellstrand.markera.series.SignInCancelledException

/** What to tell the user about [this]; the details go to the log. Null = say nothing (the user cancelled). */
fun Throwable.userMessage(): StringResource? {
    if (this is CancellationException || this is SignInCancelledException) return null
    println("Markera: $this")
    return when {
        this is SeriesApiException && isUnauthorized -> Res.string.error_session_expired
        this is SeriesApiException -> Res.string.error_server
        // Ktor timeouts and Darwin errors are IOExceptions too.
        this is kotlinx.io.IOException -> Res.string.error_network
        else -> Res.string.error_generic
    }
}
