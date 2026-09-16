# B-22 — cold start on a k0s node: met, and the two platforms are indistinguishable

**Date:** 2026-09-16. **Node:** the subject host — a 4-core, 7 GB cloud VM, Ubuntu 25.10, glibc 2.43, **k0s
v1.36.4+k0s.0** installed single-node, containers run through the node's own containerd (`k0s ctr`,
containerd 2.3.4). **Images already on the node**; the pull is not in any number here, as the item
declared before the run. **The image cache is cleared before every round** — the image is removed and
re-imported — so each round is an unpack rather than a cache hit.

**The scheduler is deliberately outside this.** Image pull policy, node selection and admission belong
to the cluster; what is measured is the node doing the work, which is what `ctr run` exercises.

**Arms:** `xyk:cold` — the shipping variant, `FROM scratch`, statically linked, with
`ktor-client-curl` and the gconv tree ([B-23](../../backlog/B-23-criterion-image-size.md)),
11 840 625 bytes — and the Go twin, 4 118 016 bytes.

## Five rounds, interleaved

| arm | import | listen | first 200 | **run → first 200** |
|---|---|---|---|---:|
| **xyk** | 0.607 – 0.761 s | 0.306 – 0.361 s | 0.043 – 0.063 s | **0.348 – 0.423 s, mean 0.394** |
| twin | 0.369 – 0.454 s | 0.323 – 0.386 s | 0.032 – 0.053 s | 0.355 – 0.440 s, mean 0.395 |

**The criterion is met, with room: 0.394 s against one second**, and the slowest single round was
0.423 s.

The first `200` is through `POST /hooks/{id}` with a genuine GitHub signature — not a probe. A probe
answers before the database has been touched, and a criterion measured on one measures the process
starting rather than the service working.

## The finding: cold start does not separate the platforms

The item predicted this was "where the two platforms are expected to differ most". **They are the
same to within the noise** — 0.394 against 0.395, with overlapping ranges in every phase that counts.

Where they do differ is the phase the criterion excludes: **import, 0.686 s against 0.401 s**, roughly
proportional to what has to be unpacked (11.8 MB against 4.1 MB). That is the image's size showing up
where image size shows up, and it is the same fact B-23 already records in bytes.

**A Kotlin/Native binary starts like a Go binary**, which is the part worth carrying out of this
repository: there is no runtime to bring up, no JIT to warm, and the 0.35 s both of them spend
getting to `listen` is containerd and the kernel rather than either language.

## One number that is not in the table

The very first import of a freshly built image took **1.451 s** against the 0.607–0.761 s of every
round after it. A snapshotter that has never seen the layer costs about twice one that has seen it
and had it removed. Rounds here all fall in the second category, which is the honest comparison
between arms — and the first-ever number is the one a node sees after a new release.

## What this does not cover

**It is not a Pod.** No kubelet, no admission, no CNI attach: those belong to the cluster and the item
excluded them. A pod's wall-clock start is this plus whatever the cluster adds, and the two should not
be added together without saying which is which.

**One node, one shape.** Four cores, local disk, a 7 GB box. The service's HTTP path is pathological
at four visible cores ([research §1.18](../research-architecture.md)) and that does not appear here,
because cold start is not throughput.
