package io.github.youndie.xyk.registry.domain

import io.github.youndie.xyk.db.isStorable
import io.github.youndie.xyk.verify.SchemeConfig

/**
 * Input-shape validation: what the operator sees when they get it wrong.
 *
 * Returns the message or `null`. Business rules that need data are typed errors on a use case;
 * these are the ones a well-formed request cannot get wrong, so they are checked before anything is
 * looked up.
 */
fun schemeProblem(
    scheme: String,
    implemented: Set<String>,
    allowUnverified: Boolean,
): String? {
    if (scheme == NO_VERIFICATION) {
        // An endpoint that verifies nothing is a public write endpoint on somebody's database.
        // Creating one takes a deliberate act at start-up as well as here.
        return if (allowUnverified) null else "scheme none is disabled"
    }
    // Deliberately the set that is IMPLEMENTED — the verifier list — and not a list written down
    // anywhere else. Accepting a scheme nothing verifies would create an endpoint whose every request
    // answers `404 unknown endpoint`, because the ingest path refuses it: configuration that looks
    // accepted and cannot work.
    return if (scheme in implemented) null else "unknown scheme: $scheme"
}

/**
 * The first text field of a request that holds NUL (U+0000), named; `null` when none does.
 *
 * Refused rather than stored, and **before anything is written**: on Kotlin/Native a NUL ends the
 * value on its way to the database, so storing would keep a shorter string than the one sent and
 * say nothing about it. Checked over the whole request first, so that a `PATCH` refused for its
 * description has not already applied its `enabled`.
 */
fun nulProblem(vararg fields: Pair<String, String?>): String? =
    fields
        .firstOrNull { (_, value) -> value != null && !isStorable(value) }
        ?.let { (name, _) -> "$name must not contain NUL" }

/** The text fields of a scheme configuration, named as the request names them. */
fun SchemeConfig?.textFields(): Array<Pair<String, String?>> =
    arrayOf(
        "schemeConfig.header" to this?.header,
        "schemeConfig.prefix" to this?.prefix,
        "schemeConfig.encoding" to this?.encoding,
    )

/**
 * A subscriber is an absolute http(s) URL and nothing else.
 *
 * Relative is refused because there is no base to resolve it against: xyk does not know its own
 * address, and guessing one from a request header is how an SSRF gets configured by accident.
 */
fun subscriberUrlProblem(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return "subscriber url must be absolute http or https"
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return "subscriber url must be absolute http or https"
    }
    val afterScheme = trimmed.substringAfter("://")
    if (afterScheme.isEmpty() || afterScheme.startsWith("/")) {
        return "subscriber url must be absolute http or https"
    }
    return null
}

/**
 * What a scheme needs in its configuration before it can work at all.
 *
 * Only the generic one needs anything, and it needs a header: without one it would refuse every
 * request, which from outside is indistinguishable from a wrong secret.
 */
fun schemeConfigProblem(
    scheme: String,
    config: SchemeConfig?,
): String? {
    if (scheme != GENERIC_HMAC) return null
    val header = config?.header?.trim()
    if (header.isNullOrEmpty()) return "scheme $GENERIC_HMAC needs schemeConfig.header"
    val encoding = config.encoding?.lowercase()
    if (encoding != null && encoding != "hex" && encoding != "base64") {
        return "schemeConfig.encoding must be hex or base64"
    }
    return null
}

const val NO_VERIFICATION: String = "none"
const val GENERIC_HMAC: String = "hmac-sha256"
