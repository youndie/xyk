package io.github.youndie.xyk.verify

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What an endpoint tells its verifier, beyond the secret.
 *
 * One bag with defaults rather than one interface per scheme: three of the five need nothing here,
 * Stripe needs a tolerance, and the generic scheme needs a header, a prefix and an encoding. Four
 * shapes would put a cast at every call site, and a verifier that ignores the fields it does not use
 * costs nothing.
 *
 * It is stored as JSON in `endpoints.scheme_config` and is null for the schemes that need no
 * configuration, which is most of them.
 */
@Serializable
class SchemeConfig(
    /**
     * Stripe only. Seconds. `0` **disables** the recency check rather than tightening it — the
     * vendor's own wording — so the configuration refuses it.
     */
    val toleranceSeconds: Long? = null,
    /** `hmac-sha256` only: which header carries the digest. */
    val header: String? = null,
    /** `hmac-sha256` only: what precedes the digest in that header, if anything. */
    val prefix: String? = null,
    /** `hmac-sha256` only: `hex` or `base64`. */
    val encoding: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        val EMPTY: SchemeConfig = SchemeConfig()

        /** Parses a stored config, or returns [EMPTY]. A broken row must not stop verification. */
        fun parse(stored: String?): SchemeConfig {
            if (stored.isNullOrBlank()) return EMPTY
            return runCatching { json.decodeFromString<SchemeConfig>(stored) }.getOrElse { EMPTY }
        }

        fun encode(config: SchemeConfig): String = json.encodeToString(config)
    }
}
