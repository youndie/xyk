package io.github.youndie.xyk.verify

/**
 * Telegram: `X-Telegram-Bot-Api-Secret-Token`, the secret echoed back verbatim.
 *
 * Read from core.telegram.org, Bot API, `setWebhook`, on 2026-09-15: 1–256 characters of
 * `A-Z a-z 0-9 _ -`.
 *
 * **This is not a signature and the journal must not call it one.** It proves the sender knows a
 * secret; it says nothing at all about the body, which could have been replaced in flight by anyone
 * who could see the header. The event records the scheme by name so that a later reader can tell how
 * much the word "verified" is worth here.
 */
class TelegramVerifier : Verifier {
    override val scheme: String = SCHEME

    override fun verify(
        request: SignedRequest,
        secrets: List<EndpointSecret>,
        config: SchemeConfig,
    ): Verdict {
        val offered = request.headers(HEADER)
        if (offered.isNullOrEmpty()) return Verdict.Refused(VerificationFailure.MISSING)

        val offeredBytes = offered.encodeToByteArray()
        for (secret in secrets) {
            if (constantTimeEquals(secret.secret.encodeToByteArray(), offeredBytes)) {
                return Verdict.Passed(secret.fingerprint)
            }
        }
        return Verdict.Refused(VerificationFailure.INVALID)
    }

    companion object {
        const val SCHEME: String = "telegram"
        const val HEADER: String = "X-Telegram-Bot-Api-Secret-Token"
    }
}
