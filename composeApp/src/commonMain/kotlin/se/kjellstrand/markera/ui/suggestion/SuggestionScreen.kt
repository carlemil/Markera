package se.kjellstrand.markera.ui.suggestion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.SeriesApi
import se.kjellstrand.markera.series.SuggestionRequest
import se.kjellstrand.markera.ui.AppTopBar
import se.kjellstrand.markera.ui.LocalOpenSuggestion
import se.kjellstrand.markera.ui.LocalToast
import se.kjellstrand.markera.ui.userMessage

/** "android" / "ios": sent with a suggestion so the developer knows which app it is about. */
expect val appPlatform: String

/** The app's version name (Android `versionName`, iOS `CFBundleShortVersionString`); null if unknown. */
expect val appVersion: String?

/** The server's limits (`server/.../Suggestions.kt`); the fields stop accepting input there. */
const val MAX_SUGGESTION_TITLE = 120
const val MAX_SUGGESTION_DESCRIPTION = 5000
private const val MAX_EMAIL = 254

/** The server's shape, and deliberately just as loose: something@something.tld without whitespace. */
private val EMAIL_SHAPE = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")

fun isPlausibleEmail(value: String): Boolean = EMAIL_SHAPE.matches(value.trim())

/**
 * What the form sends, or null while it cannot be sent: a title and a description are required, the e-mail
 * is optional but, once typed, has to look like an address.
 */
fun suggestionOrNull(title: String, description: String, email: String): SuggestionRequest? {
    val t = title.trim()
    val d = description.trim()
    val e = email.trim().ifEmpty { null }
    if (t.isEmpty() || d.isEmpty() || (e != null && !isPlausibleEmail(e))) return null
    return SuggestionRequest(t, d, e, appPlatform, appVersion)
}

/**
 * The suggestion box, from the menu on every screen: title + description (both required) and an optional
 * e-mail for a reply. The server stores it and mails it to the developer; signed out works too.
 */
@Composable
fun SuggestionScreen(api: SeriesApi, onBack: () -> Unit) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val request = suggestionOrNull(title, description, email)
    val emailInvalid = email.isNotBlank() && !isPlausibleEmail(email)
    val sentText = stringResource(Res.string.suggestion_sent)

    val send: () -> Unit = send@{
        val req = request ?: return@send
        if (sending) return@send
        sending = true
        scope.launch {
            try {
                api.postSuggestion(req)
                toast(sentText)
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                t.userMessage()?.let { toast(getString(it)) }
            } finally {
                sending = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                // The keyboard must not hide the field being typed in, nor the Send button.
                .imePadding(),
        ) {
            // The suggestion box must not offer itself.
            CompositionLocalProvider(LocalOpenSuggestion provides null) {
                AppTopBar(title = stringResource(Res.string.suggestion), onBack = onBack)
            }
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(Res.string.suggestion_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.replace('\n', ' ').take(MAX_SUGGESTION_TITLE) },
                    label = { Text(stringResource(Res.string.suggestion_title)) },
                    singleLine = true,
                    enabled = !sending,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(MAX_SUGGESTION_DESCRIPTION) },
                    label = { Text(stringResource(Res.string.suggestion_description)) },
                    minLines = 6,
                    enabled = !sending,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    supportingText = {
                        // Only once the limit is in sight; below that the count is noise.
                        if (description.length > MAX_SUGGESTION_DESCRIPTION - 500) {
                            Text("${description.length} / $MAX_SUGGESTION_DESCRIPTION")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim().take(MAX_EMAIL) },
                    label = { Text(stringResource(Res.string.suggestion_email)) },
                    singleLine = true,
                    enabled = !sending,
                    isError = emailInvalid,
                    supportingText = {
                        Text(
                            stringResource(
                                if (emailInvalid) Res.string.suggestion_email_invalid else Res.string.suggestion_email_hint,
                            ),
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = send,
                    enabled = request != null && !sending,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // The label keeps the button's width while the spinner stands in for it.
                        Text(
                            stringResource(Res.string.suggestion_send),
                            color = if (sending) Color.Transparent else Color.Unspecified,
                        )
                        if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}
