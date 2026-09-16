#!/usr/bin/env bash
# B-20: the three columns, on two machines, with the generator off the host under test.
#
#   bench/columns.sh [--rate 2000] [--duration 30s] [--rounds 3]
#
# Orchestrated from the workstation over ssh: the subject is `bench-a` (both static binaries, run
# directly — there is no docker on that box and none is wanted between the measurement and the
# thing measured), the generator is `bench-b` (k6, on the private network, about half a millisecond
# away). Neither is this machine, which is the point.
#
# WHAT THE PILOT ASKED FOR AND THIS ADDS
#
#   1. The generator is not the ceiling — `maxVUs` is sized by the rate rather than by the
#      connection count, which is what capped every arm of the pilot at ~526 rps. Measured: this
#      pair offers 8 000 rps at p50 0.5 ms with zero dropped iterations.
#   2. A SETTLE between arms, and the subject is asked whether it is idle before a round starts.
#      Back-to-back rounds interfered in the pilot: the same control arm run alone immediately
#      afterwards showed a p50 fifty times lower.
#   3. Arms interleaved, rounds repeated, the first discarded as warm-up.
#
# WHAT IS MEASURED, stated so the columns are comparable. The Kotlin arm is the **ingest-only**
# build — statically linked, no outbound engine, therefore no delivery workers. The twin has no
# delivery either. Measuring the shipping build here would put background work in one column and not
# the other, and the parity gate would be lying about what it compared.
set -uo pipefail

RATE=2000
DURATION=30s
ROUNDS=3
CONNECTIONS=200
SUBJECT=${SUBJECT:-bench-a}
GENERATOR=${GENERATOR:-bench-b}
SUBJECT_IP=${SUBJECT_IP:-10.0.0.2}
SECRET=bench-secret
ENDPOINT=hook-1
SETTLE=${SETTLE:-20}
# WHICH BINARY IS IN THE KOTLIN COLUMN. Two allocator builds are in play and the decision between
# them rests on this table plus B-21's — so the column has to be able to name which one it is rather
# than "the Kotlin one" ([B-28](../docs/backlog/B-28-allocator-decision.md)).
KOTLIN_BINARY=${KOTLIN_BINARY:-bench-xyk-new}
OUT=${OUT:-docs/research/measurements-$(date +%Y-%m-%d)}

while [ $# -gt 0 ]; do
  case "$1" in
    --rate) RATE=$2; shift 2 ;;
    --duration) DURATION=$2; shift 2 ;;
    --rounds) ROUNDS=$2; shift 2 ;;
    --connections) CONNECTIONS=$2; shift 2 ;;
    --kotlin-binary) KOTLIN_BINARY=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT/raw"
cleanup() { ssh "$SUBJECT" "pkill -x $KOTLIN_BINARY; pkill -x bench-twin-new" >/dev/null 2>&1 || true; }
trap cleanup EXIT

BODY='{"zen":"Non-blocking is better than blocking."}'
SIGNATURE=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/.*= //')

echo "=== starting both arms on $SUBJECT ==="
cat > /tmp/xyk-columns-start.sh <<REMOTE
#!/bin/bash
KOTLIN_BINARY=$KOTLIN_BINARY
REMOTE
cat >> /tmp/xyk-columns-start.sh <<'REMOTE'
pkill -x "$KOTLIN_BINARY" 2>/dev/null; pkill -x bench-twin-new 2>/dev/null
sleep 1
rm -rf /root/bench-run && mkdir -p /root/bench-run
cd /root
export XYK_BOOTSTRAP_ENDPOINT_ID=hook-1 XYK_BOOTSTRAP_SECRET=bench-secret
export XYK_BOOTSTRAP_SUBSCRIBERS=https://sink.invalid/a
XYK_DB_PATH=/root/bench-run/kotlin.db XYK_PORT=8091 nohup "./$KOTLIN_BINARY" > /root/bench-run/kotlin.log 2>&1 &
XYK_DB_PATH=/root/bench-run/go.db     XYK_PORT=8092 nohup ./bench-twin-new > /root/bench-run/go.log 2>&1 &
disown -a
for i in $(seq 1 60); do
  sleep 0.5
  curl -sf -o /dev/null http://127.0.0.1:8091/health/ready && curl -sf -o /dev/null http://127.0.0.1:8092/health/ready && { echo "both ready"; exit 0; }
done
echo "an arm never became ready" >&2
tail -3 /root/bench-run/kotlin.log /root/bench-run/go.log >&2
exit 2
REMOTE
scp -q /tmp/xyk-columns-start.sh "$SUBJECT:/tmp/" && ssh "$SUBJECT" 'bash /tmp/xyk-columns-start.sh' || exit 2

echo "=== parity, before any timing ==="
parity_case() {
  local desc=$1; shift
  local k g
  k=$(ssh "$SUBJECT" "curl -s -o /dev/null -w '%{http_code}' $* http://127.0.0.1:8091$PATH_SUFFIX")
  g=$(ssh "$SUBJECT" "curl -s -o /dev/null -w '%{http_code}' $* http://127.0.0.1:8092$PATH_SUFFIX")
  if [ "$k" != "$g" ]; then
    echo "  DISAGREE on $desc: kotlin $k, go $g" >&2
    return 1
  fi
  echo "  agree on $desc: $k"
}
PATH_SUFFIX="/hooks/$ENDPOINT"
parity_case "a genuine signature" -X POST -H "'Content-Type: application/json'" \
  -H "'X-Hub-Signature-256: sha256=$SIGNATURE'" --data "'$BODY'" || exit 1
parity_case "no signature at all" -X POST -H "'Content-Type: application/json'" --data "'$BODY'" || exit 1
PATH_SUFFIX="/hooks/does-not-exist"
parity_case "an unknown endpoint" -X POST --data "'$BODY'" || exit 1

# The scenario goes to the generator once.
scp -q bench/ingest.js "$GENERATOR:/tmp/xyk-ingest.js" || exit 2

# IS THE SUBJECT IDLE? Asked of the machine rather than assumed by the clock. A round that starts
# while the previous one's work is still draining measures the overlap, and the pilot's rounds
# interfered exactly that way.
wait_until_idle() {
  local tries=0
  while [ $tries -lt 30 ]; do
    local load
    load=$(ssh "$SUBJECT" "cut -d' ' -f1 /proc/loadavg")
    # Below one busy core on a four-core box: the arms are single processes and an idle one sits
    # near zero.
    if awk -v l="$load" 'BEGIN { exit !(l < 0.5) }'; then return 0; fi
    sleep 5
    tries=$((tries + 1))
  done
  echo "  the subject never went idle (load $(ssh "$SUBJECT" "cut -d' ' -f1 /proc/loadavg"))" >&2
  return 1
}

run_arm() {
  local arm=$1 round=$2 url
  case "$arm" in
    kotlin)  url="http://$SUBJECT_IP:8091/hooks/$ENDPOINT" ;;
    go)      url="http://$SUBJECT_IP:8092/hooks/$ENDPOINT" ;;
    control) url="http://$SUBJECT_IP:8091/health/live" ;;
  esac

  wait_until_idle || true
  ssh "$GENERATOR" "TARGET='$url' ARM='$arm' RATE=$RATE DURATION=$DURATION CONNECTIONS=$CONNECTIONS \
    BODY='$BODY' SIGNATURE='$SIGNATURE' k6 run --quiet /tmp/xyk-ingest.js" \
    > "$OUT/raw/$arm-round$round.log" 2>&1

  local reqs p50 p99 dropped failed peak
  reqs=$(grep -oE 'http_reqs[. ]+: [0-9]+ +[0-9.]+/s' "$OUT/raw/$arm-round$round.log" | grep -oE '[0-9.]+/s' | tr -d '/s')
  p50=$(grep -oE 'p\(50\)=[0-9.]+[a-zµ]*' "$OUT/raw/$arm-round$round.log" | head -1 | cut -d= -f2)
  p99=$(grep -oE 'p\(99\)=[0-9.]+[a-zµ]*' "$OUT/raw/$arm-round$round.log" | head -1 | cut -d= -f2)
  dropped=$(grep -oE 'dropped_iterations[. ]+: [0-9]+' "$OUT/raw/$arm-round$round.log" | grep -oE '[0-9]+$')
  failed=$(grep -oE 'http_req_failed[. ]+: [0-9.]+%' "$OUT/raw/$arm-round$round.log" | grep -oE '[0-9.]+%' | head -1)
  peak=$(grep -oE 'max=[0-9]+' "$OUT/raw/$arm-round$round.log" | tail -1 | cut -d= -f2)

  printf '%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$arm" "$round" "${reqs:-?}" "${p50:-?}" "${p99:-?}" "${dropped:-?}" "${failed:-?}" "${peak:-?}" \
    >> "$OUT/columns.csv"
  echo "  round $round $arm: ${reqs:-?} rps, p50 ${p50:-?}, p99 ${p99:-?}, dropped ${dropped:-?}, failed ${failed:-?}, peak concurrency ${peak:-?}"

  # The settle, between arms rather than only between rounds.
  sleep "$SETTLE"
}

echo "arm,round,rps,p50,p99,dropped,failed,peak_concurrency" > "$OUT/columns.csv"
echo "=== $ROUNDS rounds at $RATE rps, arms interleaved, round 1 discarded ==="
for round in $(seq 1 "$ROUNDS"); do
  for arm in kotlin go control; do
    run_arm "$arm" "$round"
  done
done

echo
echo "=== hosts ==="
{
  echo "subject:   $(ssh "$SUBJECT" 'hostname; nproc; ldd --version | head -1' | tr '\n' ' ')"
  echo "generator: $(ssh "$GENERATOR" 'hostname; nproc; k6 version' | tr '\n' ' ')"
  echo "rate: $RATE  duration: $DURATION  rounds: $ROUNDS  connections allowed: $CONNECTIONS"
  echo "twin driver: modernc.org/sqlite (CGO_ENABLED=0)"
  echo "kotlin arm: $KOTLIN_BINARY — static, no outbound engine, therefore no delivery workers"
} | tee "$OUT/raw/columns-hosts.txt"

echo
column -t -s, "$OUT/columns.csv"
