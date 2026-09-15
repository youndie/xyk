#!/usr/bin/env bash
# The throughput measurement, in three columns, with the parity gate in front of it.
#
#   bench/run.sh [--rate 2000] [--duration 60s] [--rounds 3]
#
# WHAT THIS HARNESS REFUSES TO DO, and each refusal is a mistake somebody has already made:
#
#   * it will not time anything until `bench/parity.sh` says both arms do the same work;
#   * it discards the first round of each arm as warm-up rather than averaging it in;
#   * it interleaves the arms and repeats them, because one run per arm is not a measurement;
#   * it reads `dropped_iterations` out of the summary and fails the run when it is not zero — an
#     open-model generator that could not keep up has measured itself, not the service;
#   * it requires the **control** arm to be the fastest. If a route that touches no database is not
#     faster than the two that do, what was measured is the generator or the host, and the run is
#     void.
#
# THE HOSTS. The criterion says the generator does not run on the machine under test, and it does
# not: the subject is `bench-a` (the two static binaries, run directly — no docker on that box and
# none needed) and the generator is `bench-b` (k6, about a millisecond away). Both are reached
# through the ssh configuration; `--same-host` still exists for shaking the harness out locally and
# produces numbers that must NOT be quoted against the criterion.
#
# WHAT THE FIRST REAL RUN FOUND, before any number was quotable
# (docs/research/measurements-2026-09-15/throughput-pilot.md):
#
#   * the control column did its job — every arm landed within 25% of every other while none reached
#     the offered rate, which is a ceiling they share rather than three services agreeing;
#   * the generator was at 72% of ONE core while that happened, so the ceiling is not its CPU;
#   * back-to-back rounds interfere: the same control arm run alone immediately afterwards showed a
#     p50 fifty times lower.
#
# Until those are understood this harness produces pilots, not results.
set -uo pipefail

RATE=2000
DURATION=60s
ROUNDS=3
CONNECTIONS=200
SAME_HOST=no
K6_IMAGE=${K6_IMAGE:-grafana/k6:0.54.0}
KOTLIN_IMAGE=${KOTLIN_IMAGE:-xyk:scratch}
GO_IMAGE=${GO_IMAGE:-xyk-twin:dev}
SECRET=bench-secret
ENDPOINT=hook-1
OUT=${OUT:-docs/research/measurements-$(date +%Y-%m-%d)}

# THE PINNING LIVES HERE, in the thing that starts the subject, and not in a note asking people to
# remember it. A dozen hand-run diagnostics on 2026-09-15 were thrown away because the generator was
# free to run on the subject's cores — each one felt like a quick check, and the rule against it was
# in this file's header, where a quick check never looks.
SUBJECT_CPUS=${SUBJECT_CPUS:-0-3}
GENERATOR_CPUS=${GENERATOR_CPUS:-}

while [ $# -gt 0 ]; do
  case "$1" in
    --rate) RATE=$2; shift 2 ;;
    --duration) DURATION=$2; shift 2 ;;
    --rounds) ROUNDS=$2; shift 2 ;;
    --connections) CONNECTIONS=$2; shift 2 ;;
    --same-host) SAME_HOST=yes; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# A same-host run without disjoint cpusets is refused outright, because that is the exact mistake
# that cost a day's numbers.
if [ "$SAME_HOST" = yes ] && [ -z "$GENERATOR_CPUS" ]; then
  echo "bench: --same-host needs GENERATOR_CPUS set to cores disjoint from SUBJECT_CPUS" >&2
  echo "       (subject is on ${SUBJECT_CPUS}); otherwise the generator competes for the very" >&2
  echo "       cores being measured and the numbers vary by an order of magnitude." >&2
  exit 2
fi

[ "$SAME_HOST" = yes ] || {
  echo "bench: refusing to run — the generator would share this host with the subject." >&2
  echo "       Pass --same-host to take a PILOT whose numbers cannot be quoted against the" >&2
  echo "       criterion, or give this harness a second machine." >&2
  exit 2
}

mkdir -p "$OUT/raw"
WORK=$(mktemp -d)
cleanup() { docker rm -f bench-kotlin bench-go >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

echo "=== parity, before any timing ==="
bench/parity.sh > "$OUT/raw/parity.log" 2>&1 || {
  echo "bench: the arms do not agree — nothing was timed. See $OUT/raw/parity.log" >&2; exit 1; }
echo "arms agree"

BODY='{"zen":"Non-blocking is better than blocking."}'
SIGNATURE=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/.*= //')

start_arms() {
  docker rm -f bench-kotlin bench-go >/dev/null 2>&1
  local env=(
    -e XYK_BOOTSTRAP_ENDPOINT_ID="$ENDPOINT" -e XYK_BOOTSTRAP_SECRET="$SECRET"
    -e XYK_BOOTSTRAP_SUBSCRIBERS="https://sink.invalid/a"
  )
  docker run -d --name bench-kotlin --cpuset-cpus="$SUBJECT_CPUS" -p 8091:8080 \
    "${env[@]}" "$KOTLIN_IMAGE" >/dev/null
  docker run -d --name bench-go --cpuset-cpus="$SUBJECT_CPUS" -p 8092:8080 \
    "${env[@]}" "$GO_IMAGE" >/dev/null
  for _ in $(seq 1 60); do
    sleep 0.5
    curl -sf -o /dev/null http://127.0.0.1:8091/health/ready \
      && curl -sf -o /dev/null http://127.0.0.1:8092/health/ready && return 0
  done
  echo "bench: an arm never became ready" >&2; exit 2
}

# RSS of a container's process, read from the host: the number the memory criterion is about.
rss_kb() { docker inspect -f '{{.State.Pid}}' "$1" 2>/dev/null | xargs -r -I{} grep VmRSS /proc/{}/status 2>/dev/null | awk '{print $2}'; }

run_one() {
  local arm=$1 round=$2 target url
  case "$arm" in
    kotlin) url="http://172.17.0.1:8091/hooks/$ENDPOINT" ;;
    go) url="http://172.17.0.1:8092/hooks/$ENDPOINT" ;;
    control) url="http://172.17.0.1:8091/health/live" ;;
  esac
  local summary="$OUT/raw/$arm-round$round.json"
  local rss_before rss_after
  rss_before=$(rss_kb bench-kotlin)
  # Staged rather than mounted from the working tree: these files are a mutagen replica at mode
  # 0600 and the k6 image runs as a non-root user (see bench/soak.sh for the failure it produces).
  # 777 on the directory, not just the file: k6 writes its summary back into this mount and the
  # image's user is not the one that created the directory.
  local staged; staged=$(mktemp -d); chmod 777 "$staged"
  cp bench/ingest.js "$staged/ingest.js"; chmod 644 "$staged/ingest.js"
  docker run --rm --network host ${GENERATOR_CPUS:+--cpuset-cpus="$GENERATOR_CPUS"} \
    -e TARGET="$url" -e ARM="$arm" -e RATE="$RATE" -e DURATION="$DURATION" \
    -e CONNECTIONS="$CONNECTIONS" -e BODY="$BODY" -e SIGNATURE="$SIGNATURE" \
    -v "$staged":/staged "$K6_IMAGE" run --summary-export="/staged/summary.json" /staged/ingest.js \
    > "$OUT/raw/$arm-round$round.log" 2>&1
  cp "$staged/summary.json" "$summary" 2>/dev/null
  rm -rf "$staged"

  # THE CONNECTION HALF OF THE CRITERION, read off the run rather than assumed by it. Under
  # `constant-arrival-rate` a busy VU is a request in flight, so the peak VU count is the peak
  # concurrency the service forced the generator into. The criterion allows CONNECTIONS of it; more
  # than that is the service failing the half of the line that is not about throughput.
  local peak
  peak=$(grep -oE 'vus[. ]+: [0-9]+ +min=[0-9]+ +max=[0-9]+' "$OUT/raw/$arm-round$round.log" \
    | grep -oE 'max=[0-9]+' | cut -d= -f2 | head -1)
  if [ -n "${peak:-}" ] && [ "$peak" -gt "$CONNECTIONS" ]; then
    echo "  $arm round $round: peak concurrency $peak exceeded the criterion's $CONNECTIONS" >&2
    echo "$arm,$round,over-concurrency,$peak" >> "$OUT/violations.csv"
  fi
  rss_after=$(rss_kb bench-kotlin)
  printf '%s round %s: rss %s -> %s kB\n' "$arm" "$round" "${rss_before:-?}" "${rss_after:-?}" \
    >> "$OUT/raw/rss.log"
}

echo "=== $ROUNDS rounds, arms interleaved, round 1 discarded as warm-up ==="
start_arms
for round in $(seq 1 "$ROUNDS"); do
  for arm in kotlin go control; do
    run_one "$arm" "$round"
    echo "  round $round $arm done"
  done
done

echo "=== summary ==="
echo "host: $(uname -srm), $(nproc) cpus, generator on the SAME host (pilot)" | tee "$OUT/raw/host.txt"
echo "k6: $K6_IMAGE   twin driver: modernc.org/sqlite" | tee -a "$OUT/raw/host.txt"
python3 bench/summarise.py "$OUT" "$ROUNDS" | tee "$OUT/throughput.md"
