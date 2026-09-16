# B-29 — the memory criterion on the configuration that actually ships

**Date:** 2026-09-16. Same host, same limit, same declared load as
[memory-declared-load.md](memory-declared-load.md) — and this time the binary is the one that goes
into the image: **statically linked with `ktor-client-curl`, four delivery workers, a real subscriber
answering in 100 ms on the subject host**.

The earlier run used the no-engine build, where `outboundPost()` returns `null`, `deliveryWorkers()`
is never constructed and nothing delivers. Its 10/10 described ingest and the journal with the whole
outbound half absent.

## The result: it still fits

| | ingest-only build | **shipping configuration** |
|---|---|---|
| survived | 10 / 10 | **10 / 10** |
| cgroup peak, kB | 54 864 – 65 656 | **60 928 – 66 108** |
| threads | 56 – 108 | **58 – 123** |
| deliveries made | none — no workers exist | **3 914** to the subscriber |
| binary | 11 517 616 B | **20 426 272 B** |

Control: the same binary at 6 MiB, killed twice out of two, before the table was allowed to exist.

**Delivery costs about 1–6 MB of peak and up to fifteen more threads, and the criterion holds.**

## And it holds without margin, which is the part to quote

Four of the ten rounds peaked at **or above** the 65 536 kB limit — 65 536, 65 536, 66 108, 66 044.
A peak at the limit is the kernel reclaiming mapped pages to fit, not a process with room to spare.
**The criterion is met by reclaim rather than by headroom**, and that is a different sentence from
"uses 60 MB of its 64".

The ingest-only run said the same thing more quietly (its top round was 65 656); adding the delivery
half moved the whole band up against the ceiling without pushing it through.

## What was asserted rather than assumed

The delivery half had to be shown to have *run*, not merely to have been linked — otherwise this is
the previous measurement again with more code in the binary. The harness asks the **subscriber** how
many deliveries arrived and voids a round that received none.

**That guard was wrong on its first outing and the mistake is worth keeping.** It voided the positive
control: a subject killed at 6 MiB delivers nothing by definition, which is exactly what the control
is for. A completeness guard applied without a scope condemns the one round whose job is to fail. It
now asks only of rounds that survived.

## What this closes and what it does not

**Closes:** the memory criterion is met by the configuration that deploys, so
[B-28](../../backlog/B-28-allocator-decision.md)'s allocator decision stands rather than re-opening —
its own acceptance line made that conditional on this run.

**Does not cover:** the ingest load and the delivery load were both present but neither was pushed to
its own ceiling in the same round. The subscriber answers in 100 ms and four workers deliver about 27
per second ([delivery-workers.md](delivery-workers.md)); a deployment with a slower subscriber and a
deeper queue holds more deliveries in flight than this run ever did.
