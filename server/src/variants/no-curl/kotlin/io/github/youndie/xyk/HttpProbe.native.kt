package io.github.youndie.xyk

/**
 * The variant built without an outbound engine — today's default, and the baseline every curl number
 * is measured against.
 */
actual fun httpEngineMarker(): String = "native: no engine linked"
