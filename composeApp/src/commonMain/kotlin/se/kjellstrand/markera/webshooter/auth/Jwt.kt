package se.kjellstrand.markera.webshooter.auth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts the `sub` claim (the user's DB id) from a JWT access token.
 * webshooter's `authenticate/user` payload does not expose the id, but the
 * mobile scoring "can't mark your own target" rule needs it to compare
 * against signup.users_id.
 */
@OptIn(ExperimentalEncodingApi::class)
fun jwtSubject(accessToken: String): Long? = try {
    val payload = accessToken.split('.').getOrNull(1) ?: return null
    val bytes = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload)
    val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    json["sub"]?.jsonPrimitive?.content?.toLongOrNull()
} catch (_: Exception) {
    null
}
