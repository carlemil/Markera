package se.kjellstrand.markera.diag

import android.app.Application
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import se.kjellstrand.markera.BuildConfig

/**
 * Starts Sentry (crashes, ANRs and whatever goes through [ErrorLog]) when a DSN is configured.
 * No DSN (`markera.sentry.dsn` missing from local.properties) leaves [ErrorLog] printing only.
 */
fun initErrorReporting(app: Application) {
    val dsn = BuildConfig.SENTRY_DSN
    if (dsn.isBlank()) return
    SentryAndroid.init(app) { options ->
        options.dsn = dsn
        // Debug builds log what the SDK does (tag "Sentry" in logcat).
        options.isDebug = BuildConfig.DEBUG
        options.environment = if (BuildConfig.DEBUG) "debug" else "release"
        // No IP address, no user: the privacy policy promises technical data only.
        options.isSendDefaultPii = false
        options.isAttachScreenshot = false
        options.isAttachViewHierarchy = false
        // Every breadcrumb also goes up as a Sentry log, error or not (Explore → Logs).
        options.logs.isEnabled = true
    }
    ErrorLog.sink = SentryErrorSink
}

private object SentryErrorSink : ErrorSink {
    override fun breadcrumb(category: String, message: String) {
        Sentry.addBreadcrumb(Breadcrumb().apply {
            this.category = category
            this.message = message
            level = SentryLevel.INFO
        })
        Sentry.logger().info("[%s] %s", category, message)
    }

    override fun report(where: String, error: Throwable, details: Map<String, String>) {
        Sentry.withScope { scope ->
            scope.setTag("where", where)
            details.forEach { (key, value) -> scope.setExtra(key, value) }
            Sentry.captureException(error)
        }
    }
}
