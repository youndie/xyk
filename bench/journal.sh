#!/usr/bin/env bash
# B-25: which half of the journal page collapses — the query or the rendering?
#
#   bench/journal.sh [--seed-seconds 40] [--readers 50] [--seconds 20]
#
# THE ARMS ARE CHOSEN TO DISCRIMINATE, not to cover. Three routes on one container, one after
# another, against the same data:
#
#   /health/ready     neither the query nor the rendering   — the control: what this host can do
#   /api/events       the SAME query, as JSON                — the query without the page
#   /journal          the query and the rendered page        — the subject
#
# If `/api/events` collapses with `/journal`, the cost is in `SELECT_EVENTS` and its three correlated
# subqueries per row. If only `/journal` does, the cost is in rendering. If neither collapses, the
# reading that started this item did not survive, and that is the result.
#
# A second dimension is the pool: `XYK_SQLITE_POOL` is read by the same binary, so the query arm can
# be re-run at 2 and at 8 connections without rebuilding anything. Two suspects, two knobs, and the
# table says which one moved.
set -uo pipefail

SEED_SECONDS=${SEED_SECONDS:-40}
READERS=50
SECONDS_PER_ARM=20
SUBJECT_CPUS=${SUBJECT_CPUS:-0-3}
GENERATOR_CPUS=${GENERATOR_CPUS:-12-19}
K6_IMAGE=${K6_IMAGE:-grafana/k6:0.54.0}
IMAGE=${IMAGE:-xyk:b25}
SECRET=bench-secret
ENDPOINT=hook-1
PORT=8087
OUT=${OUT:-/tmp/xyk-journal-$(date +%H%M%S)}

while [ $# -gt 0 ]; do
  case "$1" in
    --seed-seconds) SEED_SECONDS=$2; shift 2 ;;
    --readers) READERS=$2; shift 2 ;;
    --seconds) SECONDS_PER_ARM=$2; shift 2 ;;
    --image) IMAGE=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT"
cleanup() { docker rm -f journal-bench >/dev/null 2>&1 || true; }
trap cleanup EXIT

BODY='{"zen":"Non-blocking is better than blocking."}'
SIGNATURE=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/.*= //')

# The scenario is staged outside the working tree: it is a mutagen replica at mode 0600 and the k6
# image runs as a non-root user, which silently produces a generator that never starts.
STAGE=$(mktemp -d); chmod 755 "$STAGE"
cat > "$STAGE/seed.js" <<'JS'
import http from 'k6/http';
export const options = {
  scenarios: {
    seed: { executor: 'constant-vus', vus: 20, duration: __ENV.DURATION },
  },
};
export default function () {
  http.post(__ENV.TARGET, __ENV.BODY, {
    headers: { 'Content-Type': 'application/json', 'X-Hub-Signature-256': 'sha256=' + __ENV.SIGNATURE },
  });
}
JS
chmod 644 "$STAGE/seed.js"

cat > "$STAGE/read.js" <<'JS'
import http from 'k6/http';
import { check } from 'k6';
export const options = {
  scenarios: {
    readers: {
      executor: 'constant-vus',
      vus: Number(__ENV.READERS),
      duration: __ENV.DURATION,
    },
  },
};
export default function () {
  // A closed loop on purpose: the question is "what does a person waiting for this page see when
  // fifty of them wait together", which is a concurrency level, not an offered rate.
  const r = http.get(__ENV.TARGET, { timeout: '60s' });
  check(r, { 'ok': (res) => res.status === 200 });
}
JS
chmod 644 "$STAGE/read.js"

start_subject() {
  local pool=$1
  docker rm -f journal-bench >/dev/null 2>&1
  docker run -d --name journal-bench --cpuset-cpus="$SUBJECT_CPUS" -p "$PORT:8080" \
    -e XYK_BOOTSTRAP_ENDPOINT_ID="$ENDPOINT" -e XYK_BOOTSTRAP_SECRET="$SECRET" \
    -e XYK_BOOTSTRAP_SUBSCRIBERS="https://sink.invalid/a" \
    -e XYK_SQLITE_POOL="$pool" \
    "$IMAGE" >/dev/null || { echo "could not start the subject" >&2; exit 2; }
  for _ in $(seq 1 60); do
    sleep 0.5
    curl -sf -o /dev/null "http://127.0.0.1:$PORT/health/ready" && return 0
  done
  echo "the subject never became ready" >&2
  exit 2
}

# HOW MANY ROWS ARE ACTUALLY THERE. The first version of this harness seeded with a curl loop and
# five hundred events, found no collapse, and nearly concluded that the defect was not real — while
# the reading it was chasing came from a database that had taken a throughput run. Volume is the
# variable that was never written down, so it is measured rather than assumed.
stored_events() {
  curl -s "http://127.0.0.1:$PORT/api/stats" 2>/dev/null | grep -oE '"events":[0-9]+' | cut -d: -f2
}

seed() {
  echo "seeding for ${SEED_SECONDS}s..."
  docker run --rm --network host --cpuset-cpus="$GENERATOR_CPUS" -v "$STAGE":/staged \
    -e TARGET="http://127.0.0.1:$PORT/hooks/$ENDPOINT" -e DURATION="${SEED_SECONDS}s" \
    -e BODY="$BODY" -e SIGNATURE="$SIGNATURE" \
    "$K6_IMAGE" run --quiet /staged/seed.js > "$OUT/seed.log" 2>&1

  # THE SEED IS ASSERTED, because a benchmark over an empty table measures an empty table. This
  # harness's ancestor recorded twenty green minutes of a generator that never started.
  grep -q "http_reqs" "$OUT/seed.log" || { echo "the seeding generator produced nothing" >&2; exit 2; }
  local stored; stored=$(curl -s "http://127.0.0.1:$PORT/api/events?limit=1" | grep -o '"bodyBytes"' | wc -l)
  [ "${stored:-0}" -gt 0 ] || { echo "nothing was stored — the seed failed" >&2; exit 2; }
  echo "  seeded: $(grep -oE 'http_reqs[. ]+: [0-9]+' "$OUT/seed.log" | grep -oE '[0-9]+$') requests"
}

arm() {
  local name=$1 path=$2 pool=$3
  docker run --rm --network host --cpuset-cpus="$GENERATOR_CPUS" -v "$STAGE":/staged \
    -e TARGET="http://127.0.0.1:$PORT$path" -e READERS="$READERS" -e DURATION="${SECONDS_PER_ARM}s" \
    "$K6_IMAGE" run --quiet /staged/read.js > "$OUT/$name-pool$pool.log" 2>&1

  local reqs p50 p95 failed
  reqs=$(grep -oE 'http_reqs[. ]+: [0-9]+ +[0-9.]+/s' "$OUT/$name-pool$pool.log" | grep -oE '[0-9.]+/s' | tr -d '/s')
  p50=$(grep -E 'http_req_duration' "$OUT/$name-pool$pool.log" | grep -oE 'p\(50\)=[0-9.]+[a-z]*' | head -1 | cut -d= -f2)
  p95=$(grep -E 'http_req_duration' "$OUT/$name-pool$pool.log" | grep -oE 'p\(95\)=[0-9.]+[a-z]*' | head -1 | cut -d= -f2)
  failed=$(grep -oE 'http_req_failed[. ]+: [0-9.]+%' "$OUT/$name-pool$pool.log" | grep -oE '[0-9.]+%' | head -1)
  # ALIVE? An arm whose subject died reports zeroes that read like a slow route, and the row that
  # says "0 rps" is the same row whether the service was crawling or gone.
  local alive; alive=$(docker inspect -f '{{.State.Running}}' journal-bench 2>/dev/null)
  printf '%s,%s,%s,%s,%s,%s,%s\n' \
    "$name" "$pool" "${reqs:-?}" "${p50:-?}" "${p95:-?}" "${failed:-?}" "${alive:-gone}" >> "$OUT/results.csv"
  echo "  $name (pool $pool): ${reqs:-?} rps, p50 ${p50:-?}, p95 ${p95:-?}, failed ${failed:-?}, alive=${alive:-gone}"
}

echo "arm,pool,rps,p50,p95,failed,alive" > "$OUT/results.csv"

for pool in 2 8; do
  echo "=== pool of $pool ==="
  start_subject "$pool"
  seed
  # The control first and last would be better still; first is enough to notice a host that changed
  # under the run, and the arms are seconds apart rather than minutes.
  arm ready   /health/ready "$pool"
  arm api     "/api/events?limit=50" "$pool"
  arm journal /journal "$pool"
done

echo
echo "=== $OUT/results.csv ==="
column -t -s, "$OUT/results.csv"
