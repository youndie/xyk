#!/usr/bin/env bash
# B-14: does delivery throughput scale with workers, or is the curl dispatcher the ceiling?
#
#   bench/delivery.sh [--events 400] [--rounds 3] [--delay-ms 100]
#
# THE TWO HYPOTHESES PREDICT DIFFERENT CURVES, which is what makes this worth a run rather than an
# argument. chronik's worker delivers its batch sequentially, so parallelism is more workers. But
# `ktor-client-curl` runs all of its I/O on one `newSingleThreadContext("curl-dispatcher")`
# (research §1.6), so if that thread is the limit, more workers buy nothing.
#
#   * against a SLOW subscriber the two separate: if throughput scales with workers, the dispatcher
#     multiplexes and the ceiling is ours; if it is flat, the dispatcher is the ceiling.
#   * against an INSTANT subscriber both predict flat — which is the control. A curve that is flat in
#     both arms says the measurement could not tell them apart and the run is void.
set -uo pipefail

EVENTS=400
ROUNDS=3
DELAY_MS=100
IMAGE=${IMAGE:-xyk:delivery-bench}
SECRET=bench-secret
ENDPOINT=hook-1
PORT=8068
SINK_PORT=9100
OUT=${OUT:-docs/research/measurements-$(date +%Y-%m-%d)}

while [ $# -gt 0 ]; do
  case "$1" in
    --events) EVENTS=$2; shift 2 ;;
    --rounds) ROUNDS=$2; shift 2 ;;
    --delay-ms) DELAY_MS=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT/raw"
cleanup() { docker rm -f delivery-subject delivery-sink >/dev/null 2>&1 || true; }
trap cleanup EXIT

BODY='{"zen":"Non-blocking is better than blocking."}'
SIGNATURE=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/.*= //')

STAGE=$(mktemp -d); chmod 755 "$STAGE"
cp bench/delivery-sink.py "$STAGE/sink.py"; chmod 644 "$STAGE/sink.py"

start_sink() {
  docker rm -f delivery-sink >/dev/null 2>&1
  docker run -d --name delivery-sink --network host -v "$STAGE":/s \
    -e SINK_DELAY_MS="$1" python:3-slim python /s/sink.py >/dev/null
  for _ in $(seq 1 40); do
    sleep 0.25
    curl -sf -o /dev/null "http://127.0.0.1:$SINK_PORT/" && return 0
  done
  echo "the sink never came up" >&2; exit 2
}

run_arm() {
  local workers=$1 delay=$2 round=$3
  start_sink "$delay"
  docker rm -f delivery-subject >/dev/null 2>&1
  docker run -d --name delivery-subject --network host \
    -e XYK_PORT="$PORT" -e XYK_DELIVERY_WORKERS="$workers" \
    -e XYK_BOOTSTRAP_ENDPOINT_ID="$ENDPOINT" -e XYK_BOOTSTRAP_SECRET="$SECRET" \
    -e XYK_BOOTSTRAP_SUBSCRIBERS="http://127.0.0.1:$SINK_PORT/hook" \
    "$IMAGE" >/dev/null
  for _ in $(seq 1 60); do
    sleep 0.5
    curl -sf -o /dev/null "http://127.0.0.1:$PORT/health/ready" && break
  done

  # The events go in as fast as curl can post them, and the timers go in with them. The measurement
  # is what happens AFTER: how long the workers take to drain a queue that already exists.
  for _ in $(seq 1 "$EVENTS"); do
    curl -s -o /dev/null -X POST "http://127.0.0.1:$PORT/hooks/$ENDPOINT" \
      -H "Content-Type: application/json" -H "X-Hub-Signature-256: sha256=$SIGNATURE" -d "$BODY"
  done

  local start now delivered elapsed rate pid rss
  start=$(date +%s.%N)
  # Poll the sink rather than the journal: the sink is the thing that received, and asking the
  # subject how much it delivered would put the measurement inside the thing measured.
  for _ in $(seq 1 600); do
    delivered=$(curl -s -m 5 "http://127.0.0.1:$SINK_PORT/")
    [ "${delivered:-0}" -ge "$EVENTS" ] && break
    sleep 0.25
  done
  now=$(date +%s.%N)
  elapsed=$(awk -v a="$start" -v b="$now" 'BEGIN { printf "%.2f", b - a }')
  delivered=$(curl -s -m 5 "http://127.0.0.1:$SINK_PORT/")
  rate=$(awk -v d="${delivered:-0}" -v e="$elapsed" 'BEGIN { printf "%.1f", (e > 0 ? d / e : 0) }')
  pid=$(docker inspect -f '{{.State.Pid}}' delivery-subject 2>/dev/null)
  rss=$(grep VmRSS "/proc/$pid/status" 2>/dev/null | awk '{print $2}')

  printf '%s,%s,%s,%s,%s,%s,%s\n' "$workers" "$delay" "$round" "${delivered:-0}" "$elapsed" "$rate" "${rss:-}" \
    >> "$OUT/delivery.csv"
  echo "  workers=$workers delay=${delay}ms round=$round: ${delivered}/${EVENTS} in ${elapsed}s = ${rate}/s, rss ${rss:-?} kB"
  docker rm -f delivery-subject delivery-sink >/dev/null 2>&1
}

echo "workers,sink_delay_ms,round,delivered,seconds,per_second,rss_kb" > "$OUT/delivery.csv"
echo "=== the slow subscriber: the arm the hypotheses disagree about ==="
for round in $(seq 1 "$ROUNDS"); do
  for w in 1 2 4 8; do run_arm "$w" "$DELAY_MS" "$round"; done
done
echo "=== the instant subscriber: the control, where both predict flat ==="
for round in $(seq 1 "$ROUNDS"); do
  for w in 1 2 4 8; do run_arm "$w" 0 "$round"; done
done

echo
column -t -s, "$OUT/delivery.csv"
