package io.github.youndie.xyk

/** The JVM takes its ceiling from `-Xmx` before this code runs, so there is nothing to set here. */
actual fun applyHeapCeiling(bytes: Long): String =
    if (bytes <= 0L) "heap ceiling off (XYK_HEAP_BYTES=$bytes)" else "heap ceiling is -Xmx on the JVM; $bytes ignored"
