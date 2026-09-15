package io.github.youndie.xyk

/**
 * The JVM build ships nothing and links nothing; it exists for a fast test cycle over common code.
 * The engine question is a native one — `ktor-client-curl` has no JVM target and the JVM has several
 * engines that work.
 */
actual fun httpEngineMarker(): String = "jvm: no engine linked"
