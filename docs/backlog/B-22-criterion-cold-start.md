---
id: B-22
title: "Criterion: cold start to the first 200 under a second on a k0s node"
status: done
priority: P1
size: M
stage: stage-3-verdict
blocked_by: [B-17]
---

# B-22 — Criterion: cold start to the first 200 under a second on a k0s node

**Declared before the code, on 2026-09-15:** on a k0s node, from container start to the first `200`,
under one second.

**And declared with it, so it cannot drift afterwards: the image is already present on the node.**
Pulling bytes over somebody's uplink measures the link, not the service. The pull is reported
separately, as a bandwidth figure with the measured rate named, and it is never added to the startup
number — the two are in different units of responsibility.

- **The phases measured separately:** unpack, process start to listening, listening to first `200`
  through the real route (not a probe). Elsewhere, unpack was 0.27 s static against 1.29 s on a base
  image, and `docker run` to first answer 0.41 against 0.56 — the phases behave differently enough
  that one number hides the interesting one.
- **Decision: the first `200` is through `POST /hooks/...` with a genuine signature.** A probe answers
  before the database has been touched; a criterion that measures the probe measures the process
  starting, not the service working.
- **Decision: the image cache is cleared between rounds** on purpose, and the node is named — a
  two-core node with k0s beside it is a different number from a developer's laptop, and both are
  honest if they say which they are.
- **Decision: the same measurement is run for the Go twin.** Cold start is where the two platforms
  are expected to differ most, and a criterion measured on one arm is a number without a scale.
- Not covered: the scheduler's part — image pull policy, node selection, admission — which belongs to
  the cluster and not to xyk.

- AC: a table with the three phases, per arm, several rounds, the node described.
- AC: if the criterion is missed, the phase that missed it is named; "slow start" without the phase
  is not a result.
- Anchors: `bench/cold-start.sh`, `charts/xyk/values.yaml`

## Met, 2026-09-16 — [cold-start.md](../research/measurements-2026-09-16/cold-start.md)

k0s v1.36.4 installed single-node on `bench-a`; containers run through the node's own containerd,
images already present, cache cleared before every round, five rounds interleaved.

| arm | import | listen | first 200 | **run → first 200** |
|---|---|---|---|---:|
| **xyk** (scratch + curl, 11 840 625 B) | 0.607 – 0.761 s | 0.306 – 0.361 s | 0.043 – 0.063 s | **mean 0.394 s** |
| twin (4 118 016 B) | 0.369 – 0.454 s | 0.323 – 0.386 s | 0.032 – 0.053 s | mean 0.395 s |

**0.394 s against the declared one second**, slowest round 0.423 s. The first `200` is through
`POST /hooks/{id}` with a real signature, not a probe.

**The prediction in this item did not survive: cold start does not separate the platforms.** It was
expected to be where they differ most; they are identical to within noise. A Kotlin/Native binary
starts like a Go binary — no runtime to bring up, no JIT to warm — and the 0.35 s both spend reaching
`listen` belongs to containerd and the kernel.

Where they differ is the phase this criterion excludes: import, 0.686 s against 0.401 s, in
proportion to what has to be unpacked. That is image size showing up where image size shows up.

- AC: **met** — three phases, per arm, five rounds, the node described.
- AC: **not triggered** — the criterion was not missed, so there is no phase to name. The phase that
  would have missed it is named anyway: import, which the item excluded on purpose.
