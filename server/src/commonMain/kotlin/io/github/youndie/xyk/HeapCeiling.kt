package io.github.youndie.xyk

/**
 * Tells the GC what the kernel will kill this process for, because nothing else will.
 *
 * **A Kotlin/Native process cannot see its own cgroup limit.** The runtime grows the heap on its own
 * schedule, and under a container limit that schedule ends in the kernel rather than in a collection
 * — which is what [B-30](../../../../../../docs/backlog/B-30-delivery-memory-growth.md) is about.
 *
 * **The number is absolute and the operator sets it**, rather than a fraction of a limit this
 * process reads for itself. kore can read the cgroup (`containerMemoryBudget`), but that arrived
 * after 0.1.4 and 0.1.4 is what is published, so the automatic form waits on a release. The chart
 * already knows the limit it declares, so it is the right place to derive a number from anyway.
 *
 * Returns what happened rather than what was asked for: a ceiling that did not apply must not read
 * like one that did.
 */
expect fun applyHeapCeiling(bytes: Long): String
