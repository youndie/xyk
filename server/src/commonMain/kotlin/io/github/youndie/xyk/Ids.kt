package io.github.youndie.xyk

import org.kotlincrypto.random.CryptoRand

private const val ID_BYTES = 16
private const val HEX_DIGITS = "0123456789abcdef"

/**
 * An identifier nobody can guess, for endpoints, events, deliveries and subscribers alike.
 *
 * `CryptoRand` rather than `kotlin.random.Random`: an endpoint id appears in a URL that anyone may
 * post to, and while the signature is what authenticates a request, an enumerable set of endpoints
 * tells an attacker exactly where to aim. Making one kind of id guessable and another not would be a
 * distinction nobody maintains, so there is one generator.
 *
 * 128 bits, hex. Not a ULID: sortable ids would leak arrival order across endpoints, and the journal
 * sorts by `received_at` anyway.
 */
fun newId(): String {
    val bytes = ByteArray(ID_BYTES)
    CryptoRand.Default.nextBytes(bytes)
    val out = StringBuilder(ID_BYTES * 2)
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        out.append(HEX_DIGITS[value ushr 4])
        out.append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}
