import Foundation
import ComposeApp
import Sentry

/// Sentry behind the Kotlin `ErrorLog` (crashes, plus whatever Kotlin reports or leaves as
/// breadcrumbs). Off when `MARKERA_SENTRY_DSN` is missing from Local.xcconfig.
final class SentryReporter: NSObject, NativeErrorReporter {
    static func start() {
        guard let dsn = Bundle.main.object(forInfoDictionaryKey: "MarkeraSentryDsn") as? String,
              !dsn.isEmpty else { return }
        SentrySDK.start { options in
            options.dsn = dsn
            #if DEBUG
            options.environment = "debug"
            options.debug = true
            #else
            options.environment = "release"
            #endif
            // No IP address, no user: the privacy policy promises technical data only.
            options.sendDefaultPii = false
            options.attachScreenshot = false
            options.attachViewHierarchy = false
        }
        IosErrorReportingKt.installErrorReporter(reporter: SentryReporter())
    }

    func breadcrumb(category: String, message: String) {
        let crumb = Breadcrumb(level: .info, category: category)
        crumb.message = message
        SentrySDK.addBreadcrumb(crumb)
    }

    func report(place: String, type: String, message: String, stackTrace: String, details: [String: String]) {
        let event = Event(level: .error)
        event.exceptions = [Sentry.Exception(value: message, type: type)]
        event.tags = ["where": place]
        var extra: [String: Any] = details
        extra["kotlin_stack"] = stackTrace
        event.extra = extra
        SentrySDK.capture(event: event)
    }
}
