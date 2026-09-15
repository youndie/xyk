---
id: B-22
title: "Criterion: cold start to the first 200 under a second on a k0s node"
status: open
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
