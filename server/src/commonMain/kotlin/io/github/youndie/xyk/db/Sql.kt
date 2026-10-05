package io.github.youndie.xyk.db

import io.github.smyrgeorge.sqlx4k.Statement

/**
 * A statement whose values the driver binds: `?` in the text, the values beside it, in order.
 *
 * **This is the only way a value reaches SQL in this service.** Nothing is written into the text —
 * not an id, not a count — so there is no escape to get wrong and no reader has to check which
 * values were safe to paste. Until 2026-10-02 every value was pasted as a quoted literal, on the
 * belief that sqlx4k had nowhere to bind it (research §1.13, the correction). The quoting held, but
 * a NUL in a value still ended the statement early, and the database's error reached the response.
 *
 * Text is checked for NUL **before** it is bound, because binding alone does not make NUL safe:
 * on Kotlin/Native a TEXT parameter crosses to the driver as a C string, so everything after a NUL
 * would be dropped without a word and the row stored shorter than it was sent. [UnstorableText] is
 * thrown instead, and the server answers it with `400`.
 */
fun sql(
    text: String,
    vararg values: Any?,
): Statement {
    val statement = Statement.create(text)
    values.forEachIndexed { index, value ->
        if (value is String) requireStorable(value)
        statement.bind(index, value)
    }
    return statement
}

/** Text the database cannot hold as given: it contains NUL (U+0000). */
class UnstorableText : IllegalArgumentException(MESSAGE) {
    companion object {
        const val MESSAGE: String = "text must not contain NUL"
    }
}

/** `true` when [value] can be stored and read back as it is. */
fun isStorable(value: String): Boolean = NUL !in value

private fun requireStorable(value: String) {
    if (!isStorable(value)) throw UnstorableText()
}

private const val NUL: Char = '\u0000'
