# B-28 — the allocator, decided on both criteria

**Date:** 2026-09-16. Same two machines, same harness, same twin binary: the subject host (4 cpu) as the
subject with both binaries run directly, the generator host as the generator, 2 000 rps offered over 200
connections, three interleaved rounds with the first discarded.

## The two campaigns, side by side

| arm | `fixedBlockPageSize=16` | `-Xallocator=std` |
|---|---:|---:|
| xyk ingest, mean rps | **437** (423–452) | **379** (375–384) |
| control, no database | **434** | **331** |
| Go twin, same binary both times | 601 (590–613) | 620 (600–640) |
| failed requests | 0 % everywhere | 0 % everywhere |
| peak concurrency | 200 | 200 |
| **memory, 64 MiB, ten rounds** | **1 / 10 survived** | **10 / 10 survived** |

**The Go column is the calibration between the campaigns.** It is the same binary run a day apart:
601 against 620, overlapping ranges. The stand did not move, so the Kotlin columns can be compared
with each other — which is the one thing two separate campaigns cannot normally claim.

**The Kotlin ranges do not overlap.** 423–452 against 375–384; the 13 % gap is larger than either
arm's spread.

## The decision, and why it is easier than the numbers suggest

**`-Xallocator=std` ships.** It costs **13 % of ingest throughput** and **24 % of the
database-free route**, and it buys the difference between failing the memory criterion nine times out
of ten and meeting it ten out of ten.

**The trade is easy because only one criterion separates the arms.** Neither comes within a factor of
three of 2 000 rps — B-20's verdict is *no* for both, on this host, by a margin that 58 rps does not
change. So the throughput criterion does not choose between them; the memory criterion does, and it
chooses decisively.

**It would be a different decision on a bigger host.** §1.18 measured ~2 112 rps at twelve visible
cores against 355–816 at four, on a route of this shape. If the throughput criterion becomes
reachable somewhere, 13 % starts to matter and this should be re-run there — which is why the other
arms stay in the build rather than being deleted.

## What this retires

The inherited warning that `-Xallocator=std` "measured worse on a service with SQLite on the request
path" was the reason it was not the default here. It was true where it was measured and it does not
transfer to xyk: on this service it is the only arm that survives the declared limit at all, and its
throughput cost is 13 % rather than a regression.

The warning stays in [research §1.8](../research-architecture.md) rather than being deleted. The next
service will inherit it from the same place and should inherit this correction with it.
