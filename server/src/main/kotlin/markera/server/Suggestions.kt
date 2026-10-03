package markera.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.Date
import java.util.Properties

/** The app's suggestion box: [title] and [description] are required, [email] only if the sender wants an answer. */
@Serializable
data class SuggestionRequest(
    val title: String,
    val description: String,
    // Defaulted, like every optional field the app may leave out.
    val email: String? = null,
    /** "android" / "ios" and the app's versionName: which build the suggestion is about. */
    val platform: String? = null,
    val appVersion: String? = null,
)

/** One stored suggestion, for the admin page and the mail. */
data class SuggestionRow(
    val id: Long,
    val userId: Long?,
    val userName: String?,
    val title: String,
    val description: String,
    val email: String?,
    val platform: String?,
    val appVersion: String?,
    val createdAt: String,
    val mailedAt: String?,
)

const val MAX_SUGGESTION_TITLE = 120
const val MAX_SUGGESTION_DESCRIPTION = 5000
const val MAX_SUGGESTION_BYTES = 64 * 1024

/**
 * Suggestions accepted per 24 h from everyone together. The endpoint is open to signed-out users too, so this is
 * what keeps a script from filling the database and the mailbox; a real day brings a handful.
 */
const val MAX_SUGGESTIONS_PER_DAY = 50

/** Deliberately loose (the app checks the same shape); no whitespace also means no header injection via Reply-To. */
private val EMAIL_SHAPE = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
private val SHORT_TOKEN = Regex("[A-Za-z0-9 ._+-]{1,32}")

/** Trimmed, the title on one line, and a blank e-mail/platform/version as null. */
internal fun SuggestionRequest.normalised() = SuggestionRequest(
    title = title.trim().replace(Regex("\\s+"), " "),
    description = description.trim(),
    email = email?.trim()?.ifEmpty { null },
    platform = platform?.trim()?.ifEmpty { null },
    appVersion = appVersion?.trim()?.ifEmpty { null },
)

/** Why [req] (already [normalised]) cannot be stored, or null when it can. */
internal fun suggestionError(req: SuggestionRequest): String? = when {
    req.title.isEmpty() -> "title must not be empty"
    req.title.length > MAX_SUGGESTION_TITLE -> "title must be at most $MAX_SUGGESTION_TITLE characters"
    req.description.isEmpty() -> "description must not be empty"
    req.description.length > MAX_SUGGESTION_DESCRIPTION -> "description must be at most $MAX_SUGGESTION_DESCRIPTION characters"
    req.email != null && (req.email.length > 254 || !EMAIL_SHAPE.matches(req.email)) -> "email is not an e-mail address"
    req.platform != null && !SHORT_TOKEN.matches(req.platform) -> "platform must be a short plain token"
    req.appVersion != null && !SHORT_TOKEN.matches(req.appVersion) -> "appVersion must be a short plain token"
    else -> null
}

/** Outgoing SMTP for the suggestion mails; null (any of host/user/password missing) stores them without mailing. */
data class SmtpConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    /** Gmail rewrites any other sender to [user] anyway, so that is the default. */
    val from: String,
    val to: String,
) {
    companion object {
        fun fromEnv(contactEmail: String?): SmtpConfig? {
            fun env(name: String) = System.getenv(name)?.ifBlank { null }
            val user = env("SMTP_USER") ?: return null
            return SmtpConfig(
                host = env("SMTP_HOST") ?: return null,
                port = env("SMTP_PORT")?.toIntOrNull() ?: 587,
                user = user,
                password = env("SMTP_PASSWORD") ?: return null,
                from = env("SMTP_FROM") ?: user,
                to = env("SUGGESTIONS_TO") ?: contactEmail ?: user,
            )
        }
    }
}

data class Mail(val subject: String, val body: String, val replyTo: String?)

/** Sends one mail or throws; blocking. */
fun interface Mailer {
    fun send(mail: Mail)
}

/** STARTTLS on 587 (Gmail with an app password: `smtp.gmail.com`), implicit TLS on 465. */
class SmtpMailer(private val config: SmtpConfig) : Mailer {
    private val session: Session = Session.getInstance(
        Properties().apply {
            val implicitTls = config.port == 465
            put("mail.smtp.host", config.host)
            put("mail.smtp.port", config.port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", (!implicitTls).toString())
            put("mail.smtp.starttls.required", (!implicitTls).toString())
            put("mail.smtp.ssl.enable", implicitTls.toString())
            put("mail.smtp.ssl.checkserveridentity", "true")
            // A hung mail server must not hold the app's request open for minutes.
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
            put("mail.smtp.writetimeout", "10000")
        },
    )

    override fun send(mail: Mail) {
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.from, "Markera"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(config.to))
            mail.replyTo?.let { replyTo = arrayOf(InternetAddress(it, true)) }
            setSubject(mail.subject, "UTF-8")
            setText(mail.body, "UTF-8")
            sentDate = Date()
        }
        Transport.send(message, config.user, config.password)
    }
}

/** The mail for [s]: answering it goes straight to the sender when they left an address. */
internal fun suggestionMail(s: SuggestionRow, publicUrl: String?): Mail {
    val who = when {
        s.userId == null -> "not signed in"
        s.userName != null -> "user ${s.userId} (${s.userName})"
        else -> "user ${s.userId}"
    }
    val body = buildString {
        appendLine(s.description)
        appendLine()
        appendLine("--")
        appendLine("E-mail: ${s.email ?: "none given (no reply possible)"}")
        appendLine("Sender: $who")
        appendLine("App: ${listOfNotNull(s.platform, s.appVersion).joinToString(" ").ifEmpty { "unknown" }}")
        append("Suggestion #${s.id}")
        publicUrl?.let { append(", ${it.trimEnd('/')}/admin/suggestions") }
        appendLine()
    }
    return Mail(subject = "Markera suggestion: ${s.title}", body = body, replyTo = s.email)
}

/**
 * `POST /suggestions`. Open to signed-out users: a valid Bearer token only links the suggestion to its user, a
 * missing or stale one is not an error. Stored first, then mailed; a mail that fails is logged and the suggestion
 * stays on `/admin/suggestions` (unmailed), so the sender still gets their 201.
 */
internal suspend fun RoutingContext.postSuggestion(db: Db, mailer: Mailer?, publicUrl: String?) {
    val req = receiveCapped<SuggestionRequest>(MAX_SUGGESTION_BYTES)?.normalised() ?: return
    suggestionError(req)?.let {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
        return
    }
    if (db.suggestionsLastDay() >= MAX_SUGGESTIONS_PER_DAY) {
        call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("too many suggestions today, try again tomorrow"))
        return
    }
    val userId = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { db.userForToken(it) }
    val id = db.insertSuggestion(userId, req)
    if (mailer != null) {
        val row = db.getSuggestion(id)!!
        try {
            withContext(Dispatchers.IO) { mailer.send(suggestionMail(row, publicUrl)) }
            db.suggestionMailed(id)
        } catch (e: Exception) {
            call.application.environment.log.warn("suggestion $id stored but not mailed", e)
        }
    }
    call.respond(HttpStatusCode.Created, IdResponse(id))
}
