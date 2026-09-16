package io.github.youndie.xyk

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * `maxHeapBytes`, and **not** `targetHeapBytes`.
 *
 * The obvious call is the target and it does not hold: with `GC.autotune` on, which is the default,
 * the runtime recomputes the target from the live set after every collection, so a value written
 * before the first GC is gone by the second. `maxHeapBytes` is the bound the autotuner may not
 * cross, which is what "this container is killed above N" means.
 */
@OptIn(NativeRuntimeApi::class)
actual fun applyHeapCeiling(bytes: Long): String {
    if (bytes <= 0L) return "heap ceiling off (XYK_HEAP_BYTES=$bytes)"
    GC.maxHeapBytes = bytes
    // Read back rather than echo, so a value the runtime clamped is logged as it ended up.
    return "heap ceiling asked $bytes B, GC.maxHeapBytes is now ${GC.maxHeapBytes} B"
}
