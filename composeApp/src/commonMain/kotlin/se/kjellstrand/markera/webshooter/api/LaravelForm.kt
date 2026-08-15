package se.kjellstrand.markera.webshooter.api

import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder

/**
 * Flattens a nested map/list structure into Laravel/jQuery `$.param`-style
 * form parameters with bracket keys, e.g.
 * `audit[shots][0]=X`, `results[0][stations_id]=3`.
 *
 * This mirrors what the webshooter web client sends (Angular `$http` +
 * `$.param`): scalars via toString, booleans as "true"/"false", nulls as
 * empty strings. Ktor's FormDataContent only handles flat keys, hence this
 * helper. Keep it dependency-free and pure so it stays JVM-unit-testable.
 */
fun laravelFormEncode(root: Map<String, Any?>): Parameters {
    val builder = ParametersBuilder()
    root.forEach { (key, value) -> appendValue(builder, key, value) }
    return builder.build()
}

private fun appendValue(builder: ParametersBuilder, key: String, value: Any?) {
    when (value) {
        null -> builder.append(key, "")
        is Map<*, *> -> value.forEach { (k, v) -> appendValue(builder, "$key[$k]", v) }
        is List<*> -> value.forEachIndexed { i, v -> appendValue(builder, "$key[$i]", v) }
        is Boolean -> builder.append(key, if (value) "true" else "false")
        else -> builder.append(key, value.toString())
    }
}
