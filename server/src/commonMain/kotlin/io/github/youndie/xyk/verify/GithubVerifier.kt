package io.github.youndie.xyk.verify

import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

/**
 * GitHub: `X-Hub-Signature-256: sha256=<hex>`, HMAC-SHA256 over the **raw body**.
 *
 * Verified against docs.github.com, *Validating webhook deliveries*, on 2026-09-15.
 *
 * Every active secret is tried, because rotation keeps more than one alive; the first match wins and
 * its fingerprint is what the journal records.
 */
class GithubVerifier : Verifier {
    override val scheme: String = SCHEME

    override fun verify(
        request: SignedRequest,
        secrets: List<EndpointSecret>,
        config: SchemeConfig,
    ): Verdict {
        val header = request.headers(HEADER)?.trim()
        if (header.isNullOrEmpty()) return Verdict.Refused(VerificationFailure.MISSING)
        if (!header.startsWith(PREFIX)) return Verdict.Refused(VerificationFailure.INVALID)

        val offered = header.removePrefix(PREFIX).lowercase().encodeToByteArray()
        for (secret in secrets) {
            val expected =
                HmacSHA256(secret.secret.encodeToByteArray())
                    .doFinal(request.body)
                    .toHex()
                    .encodeToByteArray()
            if (constantTimeEquals(expected, offered)) return Verdict.Passed(secret.fingerprint)
        }
        return Verdict.Refused(VerificationFailure.INVALID)
    }

    companion object {
        const val SCHEME: String = "github"
        const val HEADER: String = "X-Hub-Signature-256"
        private const val PREFIX = "sha256="
    }
}
