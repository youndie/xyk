package io.github.youndie.xyk.db

private const val HEX = "0123456789abcdef"

/**
 * Reads a body back from `SELECT hex(body)`.
 *
 * The body goes in as a bound BLOB and comes out through `hex()` rather than as a column value,
 * because what a driver makes of a BLOB column differs between the two halves of sqlx4k, and a body
 * that survived storage and was mangled on the way out would break exactly the signatures this
 * service exists to check. `hex()` is SQLite's, so both halves read the same sixteen characters.
 *
 * Until 2026-10-02 the write was a hex literal in the SQL text, on the belief that sqlx4k could not
 * bind bytes (research §1.13, the correction). It can, on both targets, and the literal cost the
 * insert twice the body in SQL text.
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
