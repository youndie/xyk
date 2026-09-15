package io.github.youndie.xyk.registry.domain

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
    // Deliberately the set that is IMPLEMENTED, not the five the documents name. Accepting `stripe`
    // today would create an endpoint whose every request answers `404 unknown endpoint`, because the
    // ingest path refuses a scheme nothing verifies — configuration that looks accepted and cannot
    // work. The set grows with B-09 and this message grows with it.
    return if (scheme in implemented) null else "unknown scheme: $scheme"
}

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
