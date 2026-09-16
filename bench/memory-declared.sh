#!/usr/bin/env bash
# B-21 at the load the criterion actually declares, on two machines.
#
#   bench/memory-declared.sh [--limit 64M] [--rounds 10] [--duration 30s]
#
# WHY THIS EXISTS BESIDE `bench/memory.sh`. That one runs on the WSL box under docker at 200 rps over
# 50 connections, because there was no second machine. The criterion says the scenario of B-20 —
# 2 000 rps over 200 connections — and the part of it that matters to memory is **the 200
# connections**, not the 2 000 rps: B-20 measured that this service absorbs about 437 rps whatever is
# offered, so the arrival rate is bounded by its own latency. What changes with the criterion's
# scenario is the concurrency, and on this platform resident memory follows the thread count, which
# follows the concurrency.
#
# THE LIMIT IS A TRANSIENT SYSTEMD SERVICE rather than a container: `bench-a` has no docker, and a
# `MemoryMax=` unit gives the same cgroup v2 accounting — `memory.peak` and `memory.events` — which
# is what the kernel kills on. `VmHWM` is not read here; it counts the mapped pages of an eleven
# megabyte binary and once reported 9 600 kB for a container held under 8 MiB.
#
# THE POSITIVE CONTROL IS PART OF THE RUN. The same binary under a deliberately small limit must be
# killed. Two limits were guessed on another host and both survived; this one is verified here or the
# run stops before it prints a table.
set -uo pipefail

LIMIT=64M
CONTROL_LIMIT=${CONTROL_LIMIT:-6M}
ROUNDS=10
DURATION=30s
RATE=2000
CONNECTIONS=200
SUBJECT=${SUBJECT:-bench-a}
GENERATOR=${GENERATOR:-bench-b}
SUBJECT_IP=${SUBJECT_IP:-10.0.0.2}
SECRET=bench-secret
ENDPOINT=hook-1
# A PORT PER ROUND, and the reason is not tidiness. The readiness probes leave TIME-WAIT entries on
# the port, and while they exist the address cannot be bound — measured on this host: `SO_REUSEADDR`
# does not help, so the engine would refuse it too. A memory harness that reuses one port spends its
# rounds reporting a socket accident as a verdict about a limit.
PORT_BASE=8400
OUT=${OUT:-docs/research/measurements-$(date +%Y-%m-%d)}

while [ $# -gt 0 ]; do
  case "$1" in
    --limit) LIMIT=$2; shift 2 ;;
    --rounds) ROUNDS=$2; shift 2 ;;
    --duration) DURATION=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT/raw"
cleanup() { ssh "$SUBJECT" 'systemctl stop xyk-mem.service 2>/dev/null; systemctl reset-failed xyk-mem.service 2>/dev/null' >/dev/null 2>&1 || true; }
trap cleanup EXIT

BODY='{"zen":"Non-blocking is better than blocking."}'
SIGNATURE=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/.*= //')

scp -q bench/ingest.js "$GENERATOR:/tmp/xyk-ingest.js" || exit 2

cat > /tmp/xyk-mem-start.sh <<'REMOTE'
#!/bin/bash
# $1 = binary, $2 = limit, $3 = port
systemctl stop xyk-mem.service 2>/dev/null
systemctl reset-failed xyk-mem.service 2>/dev/null
rm -rf /root/mem-run && mkdir -p /root/mem-run
systemd-run --unit=xyk-mem \
  --property=MemoryMax="$2" --property=MemorySwapMax=0 \
  --setenv=XYK_DB_PATH=/root/mem-run/x.db --setenv=XYK_PORT="$3" \
  --setenv=XYK_BOOTSTRAP_ENDPOINT_ID=hook-1 --setenv=XYK_BOOTSTRAP_SECRET=bench-secret \
  --setenv=XYK_BOOTSTRAP_SUBSCRIBERS=https://sink.invalid/a \
  "/root/$1" >/dev/null 2>&1
for i in $(seq 1 60); do
  sleep 0.5
  curl -sf -o /dev/null http://127.0.0.1:$3/health/ready && { echo ready; exit 0; }
  if ! systemctl is-active --quiet xyk-mem.service; then
    # WHY IT DIED, not merely that it did. Exit 78 is `EX_CONFIG` — the service refusing a port it
    # cannot have — and counting that as "did not survive the limit" would report a memory verdict
    # about a busy socket. This harness's first version did exactly that for four rounds.
    status=$(systemctl show xyk-mem.service -p ExecMainStatus --value 2>/dev/null)
    if [ "$status" = 78 ]; then echo "port-busy"; else echo "died-before-serving"; fi
    exit 1
  fi
done
echo "never-ready"
exit 1
REMOTE
scp -q /tmp/xyk-mem-start.sh "$SUBJECT:/tmp/"

cat > /tmp/xyk-mem-read.sh <<'REMOTE'
#!/bin/bash
# $1 = port. peak kB, oom kills, threads, alive — read from the cgroup the kernel actually kills on.
cg=/sys/fs/cgroup/system.slice/xyk-mem.service
peak=$(awk '{printf "%d", $1/1024}' "$cg/memory.peak" 2>/dev/null)
oom=$(awk '/^oom_kill /{print $2}' "$cg/memory.events" 2>/dev/null)
pid=$(systemctl show xyk-mem.service -p MainPID --value 2>/dev/null)
threads=$(grep Threads "/proc/$pid/status" 2>/dev/null | awk '{print $2}')
alive=$(systemctl is-active xyk-mem.service 2>/dev/null)
events=$(curl -s -m 5 "http://127.0.0.1:$1/api/events?limit=1" 2>/dev/null | grep -c '"bodyBytes"')
echo "${peak:-0},${oom:-0},${threads:-0},${alive:-gone},${events:-0}"
REMOTE
scp -q /tmp/xyk-mem-read.sh "$SUBJECT:/tmp/"

run_round() {
  local arm=$1 limit=$2 round=$3
  local started
  local port=$((PORT_BASE + ROUND_SEQ)); ROUND_SEQ=$((ROUND_SEQ + 1))
  started=$(ssh "$SUBJECT" "bash /tmp/xyk-mem-start.sh xyk-$arm $limit $port")
  if [ "$started" = port-busy ]; then
    # Not a result about memory at all. The run stops rather than recording a row that would be read
    # as one — a harness that cannot get its subject started has measured nothing.
    echo "  $arm $limit round $round: the port was busy — this says nothing about the limit" >&2
    echo "  (something else holds $port on $SUBJECT, or the previous unit had not released it)" >&2
    exit 2
  fi
  if [ "$started" != ready ]; then
    printf '%s,%s,%s,%s,,,,\n' "$arm" "$limit" "$round" "$started" >> "$OUT/memory.csv"
    echo "  $arm $limit round $round: $started"
    ssh "$SUBJECT" 'systemctl stop xyk-mem.service 2>/dev/null; systemctl reset-failed xyk-mem.service 2>/dev/null' >/dev/null 2>&1
    # Long enough for the kernel to let go of the port: a round that starts into the previous
    # round's socket reports a memory verdict about a timing accident.
    sleep 5
    return
  fi

  ssh "$GENERATOR" "TARGET='http://$SUBJECT_IP:$port/hooks/$ENDPOINT' ARM=ingest RATE=$RATE \
    DURATION=$DURATION CONNECTIONS=$CONNECTIONS BODY='$BODY' SIGNATURE='$SIGNATURE' \
    k6 run --quiet /tmp/xyk-ingest.js" > "$OUT/raw/mem-$arm-$limit-$round.log" 2>&1

  local reading peak oom threads alive events
  reading=$(ssh "$SUBJECT" "bash /tmp/xyk-mem-read.sh $port")
  IFS=, read -r peak oom threads alive events <<< "$reading"

  # DID THE LOAD ARRIVE? Asked of the subject, because a generator that failed to start looks
  # exactly like a service that is coping — and this repository has already published a table of
  # ten survivals taken with no load at all.
  if [ "$alive" = active ] && [ "${events:-0}" -eq 0 ]; then
    echo "  $arm $limit round $round: NO LOAD REACHED THE SUBJECT — void" >&2
    printf '%s,%s,%s,no-load,,,,\n' "$arm" "$limit" "$round" >> "$OUT/memory.csv"
    return
  fi

  local killed=false
  [ "${oom:-0}" -gt 0 ] && killed=true
  [ "$alive" = active ] || killed=true

  printf '%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$arm" "$limit" "$round" "$killed" "${peak:-}" "${threads:-}" "${events:-}" "$alive" \
    >> "$OUT/memory.csv"
  echo "  $arm $limit round $round: killed=$killed peak=${peak}kB threads=${threads} alive=$alive"
  ssh "$SUBJECT" 'systemctl stop xyk-mem.service 2>/dev/null; systemctl reset-failed xyk-mem.service 2>/dev/null' >/dev/null 2>&1
  sleep 10
}

ROUND_SEQ=0
echo "arm,limit,round,killed,peak_kb,threads,events,state" > "$OUT/memory.csv"

echo "=== the positive control: the shipping arm at $CONTROL_LIMIT must be killed ==="
CONTROL_ARM=${CONTROL_ARM:-fixed16}
for round in 1 2; do run_round "$CONTROL_ARM" "$CONTROL_LIMIT" "c$round"; done
if ! grep -qE ",$CONTROL_LIMIT,c[12],(true|died-before-serving)" "$OUT/memory.csv"; then
  echo "VOID: nothing died at $CONTROL_LIMIT, so this harness cannot detect a death." >&2
  echo "      Results are in $OUT/memory.csv and must not be quoted." >&2
  exit 1
fi
echo "control died as it must"

echo "=== $ROUNDS rounds per arm at $LIMIT, $RATE rps over $CONNECTIONS connections, interleaved ==="
for round in $(seq 1 "$ROUNDS"); do
  for arm in ${ARMS:-fixed16 std}; do
    run_round "$arm" "$LIMIT" "$round"
  done
  echo "  round $round done"
done

echo
echo "=== $OUT/memory.csv ==="
column -t -s, "$OUT/memory.csv"
