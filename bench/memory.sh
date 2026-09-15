#!/usr/bin/env bash
# B-21: does the service survive its declared memory limit, ten runs out of ten?
#
#   bench/memory.sh [--limit 64m] [--rounds 10] [--duration 15s]
#
# FOUR ARMS, INTERLEAVED. Three of them are a different allocator linked into the binary
# (`-Pxyk.allocator=`), the fourth is `MALLOC_ARENA_MAX=2` on top of the shipping one — an
# environment variable, so it needs no build of its own.
#
# THE POSITIVE CONTROL IS PART OF THE MEASUREMENT, not an extra. The same image under a deliberately
# small limit **must** be killed; if nothing dies there, this harness cannot detect a death and
# "10/10 survived" is a statement about the harness. A run whose control survives is void.
#
# WHAT IS RECORDED, AND THE FIRST VERSION OF THIS WAS WRONG. Peak memory comes from the cgroup's own
# `memory.peak`, and the kill count from `memory.events`' `oom_kill` — not from the process's
# `VmHWM`, which was the first attempt. `VmHWM` counts the mapped pages of a ten-megabyte binary
# among other things, so it reported 9 600 kB for a container the kernel was holding under an eight
# megabyte limit: a number larger than the limit it is supposedly measured against, which is the
# tell. The kernel kills on what it accounts, so that is what gets recorded.
#
# The peak thread count is recorded beside it because on this platform resident memory follows the
# thread count rather than the live heap, and a peak without threads has no mechanism attached.
set -uo pipefail

LIMIT=64m
# 6 MiB, and the number was found twice, because the first search used the wrong metric. Measured by
# the cgroup — which is what the kernel kills on — the service starts and serves inside **8 MiB**,
# peaking at exactly the limit as the kernel reclaims the mapped pages of the binary to fit, and it
# does not come up at 6. Two earlier guesses (24 MiB, then 8 MiB) survived, and the harness refused
# to go on both times: a control that does not die proves nothing about a survival.
CONTROL_LIMIT=6m
ROUNDS=10
DURATION=15s
RATE=${RATE:-200}
SUBJECT_CPUS=${SUBJECT_CPUS:-0-3}
GENERATOR_CPUS=${GENERATOR_CPUS:-12-19}
K6_IMAGE=${K6_IMAGE:-grafana/k6:0.54.0}
SECRET=bench-secret
ENDPOINT=hook-1
OUT=${OUT:-/tmp/xyk-memory-$(date +%H%M%S)}

while [ $# -gt 0 ]; do
  case "$1" in
    --limit) LIMIT=$2; shift 2 ;;
    --rounds) ROUNDS=$2; shift 2 ;;
    --duration) DURATION=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT"
cleanup() { docker rm -f mem-arm >/dev/null 2>&1 || true; }
trap cleanup EXIT

BODY='{"zen":"Non-blocking is better than blocking."}'
SIGNATURE=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/.*= //')

# arm -> image, extra docker arguments
arm_image() { case "$1" in
  default) echo "xyk-mem:default" ;;
  fixed16|fixed16-arena2) echo "xyk-mem:fixed16" ;;
  std) echo "xyk-mem:std" ;;
esac; }
arm_env() { case "$1" in
  fixed16-arena2) echo "-e MALLOC_ARENA_MAX=2" ;;
  *) echo "" ;;
esac; }

run_once() {
  local arm=$1 limit=$2 round=$3
  local image; image=$(arm_image "$arm")
  docker rm -f mem-arm >/dev/null 2>&1
  # shellcheck disable=SC2046
  docker run -d --name mem-arm --memory="$limit" --memory-swap="$limit" \
    --cpuset-cpus="$SUBJECT_CPUS" -p 8061:8080 \
    -e XYK_BOOTSTRAP_ENDPOINT_ID="$ENDPOINT" -e XYK_BOOTSTRAP_SECRET="$SECRET" \
    -e XYK_BOOTSTRAP_SUBSCRIBERS="https://sink.invalid/a" \
    $(arm_env "$arm") "$image" >/dev/null || { echo "$arm round $round: could not start" >&2; return; }

  local ready=no
  for _ in $(seq 1 40); do
    sleep 0.5
    curl -sf -o /dev/null http://127.0.0.1:8061/health/ready && { ready=yes; break; }
  done
  if [ "$ready" != yes ]; then
    printf '%s,%s,%s,died-before-serving,,\n' "$arm" "$limit" "$round" >> "$OUT/results.csv"
    return
  fi

  # STAGED, NOT MOUNTED FROM THE WORKING TREE. The first version of this harness mounted
  # `$PWD/bench` and every one of its forty rounds died on `stat /bench/ingest.js: permission
  # denied` — the tree is a mutagen replica at mode 0600 and the k6 image runs as a non-root user.
  # The rounds then scored 10/10 survivals for a process nothing was talking to.
  local staged; staged=$(mktemp -d); chmod 755 "$staged"
  cp bench/ingest.js "$staged/ingest.js"; chmod 644 "$staged/ingest.js"
  docker run --rm --network host --cpuset-cpus="$GENERATOR_CPUS" -v "$staged":/staged \
    -e TARGET="http://127.0.0.1:8061/hooks/$ENDPOINT" -e ARM=ingest -e RATE="$RATE" \
    -e DURATION="$DURATION" -e CONNECTIONS=50 -e BODY="$BODY" -e SIGNATURE="$SIGNATURE" \
    "$K6_IMAGE" run --quiet /staged/ingest.js > "$OUT/$arm-$limit-$round.log" 2>&1
  rm -rf "$staged"

  # DID THIS ROUND CARRY ANY LOAD? Asked of the generator's own output, per round, because a
  # generator that never started is indistinguishable from a subject that is coping: flat memory,
  # flat threads, every probe 200. A round with no load is not a survival and must not be counted
  # as one.
  if ! grep -q "http_reqs" "$OUT/$arm-$limit-$round.log"; then
    printf '%s,%s,%s,no-load,,\n' "$arm" "$limit" "$round" >> "$OUT/results.csv"
    echo "$arm round $round: THE GENERATOR PRODUCED NO REQUESTS (see $OUT/$arm-$limit-$round.log)" >&2
    docker rm -f mem-arm >/dev/null 2>&1
    return
  fi

  local pid peak threads killed cgroup
  pid=$(docker inspect -f '{{.State.Pid}}' mem-arm 2>/dev/null)
  cgroup=/sys/fs/cgroup/system.slice/docker-$(docker inspect -f '{{.Id}}' mem-arm 2>/dev/null).scope
  # Kilobytes, to keep the column comparable with everything else written in this repository.
  peak=$(awk '{printf "%d", $1/1024}' "$cgroup/memory.peak" 2>/dev/null)
  killed=$(awk '/^oom_kill /{print ($2 > 0) ? "true" : "false"}' "$cgroup/memory.events" 2>/dev/null)
  threads=$(grep Threads "/proc/$pid/status" 2>/dev/null | awk '{print $2}')
  # A container that is gone was killed, whatever any file says after the fact.
  [ "$(docker inspect -f '{{.State.Running}}' mem-arm 2>/dev/null)" = "true" ] || killed=true
  printf '%s,%s,%s,%s,%s,%s\n' "$arm" "$limit" "$round" "${killed:-unknown}" "${peak:-}" "${threads:-}" \
    >> "$OUT/results.csv"
  docker rm -f mem-arm >/dev/null 2>&1
}

echo "arm,limit,round,oom_killed,peak_rss_kb,threads" > "$OUT/results.csv"

echo "=== the positive control: the shipping arm at $CONTROL_LIMIT must be killed ==="
for round in 1 2; do run_once fixed16 "$CONTROL_LIMIT" "$round"; done
if ! grep -q ",$CONTROL_LIMIT,.*,true\|,$CONTROL_LIMIT,.*died-before-serving" "$OUT/results.csv"; then
  echo "VOID: nothing died at $CONTROL_LIMIT, so this harness cannot detect a death." >&2
  echo "      Results are in $OUT/results.csv and must not be quoted." >&2
  exit 1
fi
echo "control died as it must"

echo "=== $ROUNDS rounds per arm at $LIMIT, interleaved ==="
for round in $(seq 1 "$ROUNDS"); do
  for arm in default fixed16 fixed16-arena2 std; do
    run_once "$arm" "$LIMIT" "$round"
  done
  echo "  round $round done"
done

# NO TABLE OVER A ROUND THAT CARRIED NO LOAD. This gate is the one the first version of the harness
# did not have, and its absence cost a whole document: forty rounds scored 10/10 against a server
# that received nothing.
if grep -q ",no-load," "$OUT/results.csv"; then
  echo "VOID: $(grep -c ',no-load,' "$OUT/results.csv") round(s) carried no load. The generator did not run." >&2
  echo "      Results are in $OUT/results.csv and must not be quoted." >&2
  exit 1
fi

echo "=== results ==="
python3 bench/memory-summary.py "$OUT/results.csv" "$LIMIT" | tee "$OUT/summary.md"
echo "raw: $OUT/results.csv"
