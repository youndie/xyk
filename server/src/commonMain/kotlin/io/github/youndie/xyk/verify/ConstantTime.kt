package io.github.youndie.xyk.verify

/**
 * Compares two byte sequences in time that does not depend on where they first differ.
 *
 * kotlincrypto ships no such helper, so this is ours. The risk it guards against is small and the
 * risk it *creates* is larger: the danger is not that this is wrong — it is fourteen lines — but
 * that some path stops calling it, and no test that runs in a suite would notice. Every comparison
 * of secret material in this package goes through here, and that is a review rule, not an assertion
 * a machine makes.
 *
 * Length is compared first and separately. Hiding it would mean hashing both sides, and the length
 * of a signature is public anyway — it is fixed by the scheme.
 */
fun constantTimeEquals(
    a: ByteArray,
    b: ByteArray,
): Boolean {
    if (a.size != b.size) return false
    var difference = 0
    for (i in a.indices) {
        difference = difference or (a[i].toInt() xor b[i].toInt())
    }
    return difference == 0
}

private const val HEX_DIGITS = "0123456789abcdef"

/** Lowercase hex, which is what every scheme here compares. */
fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(HEX_DIGITS[value ushr 4])
        out.append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}
