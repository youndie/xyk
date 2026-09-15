package io.github.youndie.xyk.db

private const val HEX = "0123456789abcdef"

/**
 * Renders bytes as a SQLite blob literal — `X'48656c6c6f'`.
 *
 * **Why not a parameter.** sqlx4k's `Statement` is a renderer, not a prepared statement: `bind`
 * collects values and `renderNativeQuery` writes them into the SQL text. There is no byte-array
 * encoder in that path, and inventing one through `ValueEncoderRegistry` would put a hand-written
 * escape on the one field an attacker controls completely.
 *
 * A hex literal sidesteps the question. The output is sixteen possible characters and nothing else,
 * so nothing in a webhook body can end the literal early, and SQLite stores a real BLOB — `length()`
 * and `hex()` work on it, and nothing has been transcoded. The price is that the SQL text is twice
 * the body while the insert runs, which is why the body limit is checked **before** the read.
 */
fun ByteArray.toSqliteBlobLiteral(): String {
    val out = StringBuilder(size * 2 + 3)
    out.append("X'")
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(HEX[value ushr 4])
        out.append(HEX[value and 0x0F])
    }
    out.append('\'')
    return out.toString()
}

/**
 * Reads back what [toSqliteBlobLiteral] wrote, from `SELECT hex(body)`.
 *
 * The read goes through `hex()` for the same reason the write goes through a literal: what a driver
 * makes of a BLOB column differs between the two halves of sqlx4k, and a body that survived storage
 * and was mangled on the way out would break exactly the signatures this service exists to check.
 */
fun String.fromSqliteHex(): ByteArray {
    require(length % 2 == 0) { "hex of odd length: $length" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val high = HEX.indexOf(this[i * 2].lowercaseChar())
        val low = HEX.indexOf(this[i * 2 + 1].lowercaseChar())
        require(high >= 0 && low >= 0) { "not hex at ${i * 2}" }
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}
