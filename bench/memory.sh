#!/usr/bin/env bash
# B-21: does the service survive its declared memory limit, ten runs out of ten?
#
#   bench/memory.sh [--limit 64m] [--rounds 10] [--duration 15s]
#   ARMS="a0=xyk-g1:paged-off|MALLOC_ARENA_MAX=2 a1=xyk-g1:paged-off" CONTROL_ARM=a0 bench/memory.sh
#
# ARMS, INTERLEAVED. B-21's four were a different allocator linked into the binary
# (`-Pxyk.allocator=`) plus `MALLOC_ARENA_MAX=2` on top of the shipping one — an environment
# variable, so it needs no build of its own. They are the default of `ARMS`; B-32 passes its own.
# An arm is `name=image` followed by `|VAR=value` for every variable it adds; an environment variable
# the IMAGE carries cannot be removed by `docker run`, so an arm "without" one needs an image built
# without it (B-32 re-imports the shipping image's filesystem with that line left out).
#
# THE ORDER ROTATES BY ROUND. Every arm runs once per round, and round r starts at arm r, so a drift
# across a round (the host warming, a neighbour starting) lands on every arm in turn instead of on
# whichever one is always last.
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
#
# CPU PER REQUEST, NOT REQUESTS PER SECOND (B-32). The container's own `cpu.stat` `usage_usec` is
# read just before the generator starts and just after it stops; divided by the requests answered
# `200` it is the cost of a request, which rps at a fixed offered rate cannot show. Beside it the
# whole host's busy time from `/proc/stat` over the same window: the part that is not the subject is
# the generator plus whatever else the machine was doing, and a round where that jumps is a round a
# neighbour shared. The generator's latencies come from k6's own summary export.
#
# THE LEVER, READ FROM THE PROCESS (B-32, needs passwordless sudo, otherwise left blank): the
# `MALLOC_ARENA_MAX` the subject actually received, from `/proc/<pid>/environ`, and how many glibc
# heaps it created — anonymous read-write mappings starting on a 64 MiB boundary, which is how glibc
# places every arena but the main one. A cap that is never reached is a cap that changes nothing,
# and only this column says which.
#
# THE GENERATOR TALKS TO THE CONTAINER'S OWN ADDRESS, not to a published port: `-p` puts
# `docker-proxy` between them, a host process on no cpuset, whose CPU lands on whatever core is free
# — the subject's included — and is charged to nobody.
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
CONNECTIONS=${CONNECTIONS:-50}
SUBJECT_CPUS=${SUBJECT_CPUS:-0-3}
GENERATOR_CPUS=${GENERATOR_CPUS:-12-19}
K6_IMAGE=${K6_IMAGE:-grafana/k6:0.54.0}
SECRET=bench-secret
ENDPOINT=hook-1
OUT=${OUT:-/tmp/xyk-memory-$(date +%H%M%S)}
ARMS=${ARMS:-"default=xyk-mem:default fixed16=xyk-mem:fixed16 fixed16-arena2=xyk-mem:fixed16|MALLOC_ARENA_MAX=2 std=xyk-mem:std"}
# The arm the positive control runs: the shipping one, under a limit it cannot survive.
CONTROL_ARM=${CONTROL_ARM:-fixed16}

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

# arm -> image, extra docker arguments, both read out of ARMS
arm_spec() { local a; for a in $ARMS; do [ "${a%%=*}" = "$1" ] && { echo "${a#*=}"; return; }; done; }
arm_names() { local a; for a in $ARMS; do echo "${a%%=*}"; done; }
arm_image() { local spec; spec=$(arm_spec "$1"); echo "${spec%%|*}"; }
arm_env() {
  local spec rest; spec=$(arm_spec "$1")
  case "$spec" in *"|"*) rest=${spec#*|} ;; *) return ;; esac
  local IFS='|' v; for v in $rest; do printf -- '-e %s ' "$v"; done
}
for a in $(arm_names) "$CONTROL_ARM"; do
  [ -n "$(arm_image "$a")" ] || { echo "arm '$a' is not in ARMS" >&2; exit 2; }
done
host_busy_usec() { awk '/^cpu /{print ($2+$3+$4+$7+$8+$9) * 10000; exit}' /proc/stat; }
LEVER=no; sudo -n true 2>/dev/null && LEVER=yes

run_once() {
  local arm=$1 limit=$2 round=$3
  local image; image=$(arm_image "$arm")
  docker rm -f mem-arm >/dev/null 2>&1
  # shellcheck disable=SC2046
  docker run -d --name mem-arm --memory="$limit" --memory-swap="$limit" \
    --cpuset-cpus="$SUBJECT_CPUS" \
    -e XYK_BOOTSTRAP_ENDPOINT_ID="$ENDPOINT" -e XYK_BOOTSTRAP_SECRET="$SECRET" \
    -e XYK_BOOTSTRAP_SUBSCRIBERS="https://sink.invalid/a" \
    $(arm_env "$arm") "$image" >/dev/null || { echo "$arm round $round: could not start" >&2; return; }

  local ready=no ip
  ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' mem-arm 2>/dev/null)
  for _ in $(seq 1 40); do
    sleep 0.5
    curl -sf -o /dev/null "http://$ip:8080/health/ready" && { ready=yes; break; }
  done
  if [ "$ready" != yes ]; then
    printf '%s,%s,%s,died-before-serving,,%s\n' "$arm" "$limit" "$round" ",,,,,,,,,,,,," >> "$OUT/results.csv"
    return
  fi
  local pid cgroup cpu0 host0
  pid=$(docker inspect -f '{{.State.Pid}}' mem-arm 2>/dev/null)
  cgroup=/sys/fs/cgroup/system.slice/docker-$(docker inspect -f '{{.Id}}' mem-arm 2>/dev/null).scope
  cpu0=$(awk '/^usage_usec /{print $2}' "$cgroup/cpu.stat" 2>/dev/null)
  host0=$(host_busy_usec)

  # STAGED, NOT MOUNTED FROM THE WORKING TREE. The first version of this harness mounted
  # `$PWD/bench` and every one of its forty rounds died on `stat /bench/ingest.js: permission
  # denied` — the tree is a mutagen replica at mode 0600 and the k6 image runs as a non-root user.
  # The rounds then scored 10/10 survivals for a process nothing was talking to.
  local staged; staged=$(mktemp -d); chmod 777 "$staged"
  cp bench/ingest.js "$staged/ingest.js"; chmod 644 "$staged/ingest.js"
  docker run --rm --network host --cpuset-cpus="$GENERATOR_CPUS" -v "$staged":/staged \
    -e TARGET="http://$ip:8080/hooks/$ENDPOINT" -e ARM=ingest -e RATE="$RATE" \
    -e DURATION="$DURATION" -e CONNECTIONS="$CONNECTIONS" -e BODY="$BODY" -e SIGNATURE="$SIGNATURE" \
    "$K6_IMAGE" run --quiet --summary-export /staged/summary.json /staged/ingest.js \
    > "$OUT/$arm-$limit-$round.log" 2>&1
  local cpu1 host1
  cpu1=$(awk '/^usage_usec /{print $2}' "$cgroup/cpu.stat" 2>/dev/null)
  host1=$(host_busy_usec)
  [ -f "$staged/summary.json" ] && cp "$staged/summary.json" "$OUT/$arm-$limit-$round.json"
  rm -rf "$staged"

  # DID THIS ROUND CARRY ANY LOAD? Asked of the generator's own output, per round, because a
  # generator that never started is indistinguishable from a subject that is coping: flat memory,
  # flat threads, every probe 200. A round with no load is not a survival and must not be counted
  # as one.
  if ! grep -q "http_reqs" "$OUT/$arm-$limit-$round.log"; then
    printf '%s,%s,%s,no-load,,%s\n' "$arm" "$limit" "$round" ",,,,,,,,,,,,," >> "$OUT/results.csv"
    echo "$arm round $round: THE GENERATOR PRODUCED NO REQUESTS (see $OUT/$arm-$limit-$round.log)" >&2
    docker rm -f mem-arm >/dev/null 2>&1
    return
  fi

  local peak threads killed arena_env="" arenas="" nproc_in k6
  # Kilobytes, to keep the column comparable with everything else written in this repository.
  peak=$(awk '{printf "%d", $1/1024}' "$cgroup/memory.peak" 2>/dev/null)
  killed=$(awk '/^oom_kill /{print ($2 > 0) ? "true" : "false"}' "$cgroup/memory.events" 2>/dev/null)
  threads=$(grep Threads "/proc/$pid/status" 2>/dev/null | awk '{print $2}')
  # A container that is gone was killed, whatever any file says after the fact.
  [ "$(docker inspect -f '{{.State.Running}}' mem-arm 2>/dev/null)" = "true" ] || killed=true
  if [ "$LEVER" = yes ] && [ "$killed" = false ]; then
    arena_env=$(sudo -n cat "/proc/$pid/environ" 2>/dev/null | tr '\0' '\n' | sed -n 's/^MALLOC_ARENA_MAX=//p')
    arena_env=${arena_env:-unset}
    arenas=$(sudo -n cat "/proc/$pid/maps" 2>/dev/null | python3 -c '
import sys
print(sum(1 for f in (l.split() for l in sys.stdin)
          if len(f) == 5 and f[1] == "rw-p" and int(f[0].split("-")[0], 16) % (64 << 20) == 0))')
  fi
  nproc_in=$(awk '/^Cpus_allowed_list/{print $2}' "/proc/$pid/status" 2>/dev/null)
  # What `memory.peak` is made of, at the end of the round: anonymous memory (the heap, malloc's
  # arenas, thread stacks) against file pages (the binary's mapping, SQLite's database and WAL in the
  # page cache). Under a roomy limit the second grows into whatever room there is and the peak
  # follows it; under a tight one the kernel takes it back first. Only the first is the allocator's.
  local anon file
  anon=$(awk '/^anon /{printf "%d", $2/1024}' "$cgroup/memory.stat" 2>/dev/null)
  file=$(awk '/^file /{printf "%d", $2/1024}' "$cgroup/memory.stat" 2>/dev/null)
  # requests answered 200, all requests, dropped iterations, p50, p99 — from k6's own export
  k6=$(python3 - "$OUT/$arm-$limit-$round.json" <<'PY'
import json, sys
try:
    m = json.load(open(sys.argv[1]))["metrics"]
except Exception:
    print(",,,,"); sys.exit()
ok = m.get("checks", {}).get("passes", "")
reqs = m.get("http_reqs", {}).get("count", "")
dropped = m.get("dropped_iterations", {}).get("count", 0)
d = m.get("http_req_duration", {})
print(f"{ok},{reqs},{dropped},{d.get('p(50)', '')},{d.get('p(99)', '')}")
PY
)
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "$arm" "$limit" "$round" "${killed:-unknown}" \
    "${peak:-}" "${threads:-}" "$(( ${cpu1:-0} - ${cpu0:-0} ))" "$k6" "$(( host1 - host0 ))" \
    "$arena_env" "$arenas" "$(cut -d' ' -f1 /proc/loadavg)" "$nproc_in" "${anon:-}" "${file:-}" >> "$OUT/results.csv"
  docker rm -f mem-arm >/dev/null 2>&1
}

# The header every log of a measurement carries: where, on what, with what — so that two tables are
# never set side by side without knowing whether they came from one host.
{
  echo "# host=$(hostname) kernel=$(uname -r) nproc_host=$(nproc) uptime=\"$(uptime)\""
  echo "# commit=$(git rev-parse HEAD 2>/dev/null || echo unknown) dirty=$(git status --porcelain 2>/dev/null | grep -c .)"
  echo "# generator=$K6_IMAGE generator_cpus=$GENERATOR_CPUS subject_cpus=$SUBJECT_CPUS rate=$RATE connections=$CONNECTIONS duration=$DURATION limit=$LIMIT control=$CONTROL_ARM@$CONTROL_LIMIT lever=$LEVER"
  for a in $(arm_names); do
    echo "# arm $a = $(arm_spec "$a") image_id=$(docker image inspect -f '{{.Id}}' "$(arm_image "$a")" 2>/dev/null)"
  done
} | tee "$OUT/header.txt"

echo "arm,limit,round,oom_killed,peak_rss_kb,threads,cpu_usec,reqs_ok,reqs,dropped,p50_ms,p99_ms,host_busy_usec,arena_env,arenas,load1,subject_cpus,anon_kb,file_kb" \
  > "$OUT/results.csv"

echo "=== the positive control: $CONTROL_ARM at $CONTROL_LIMIT must be killed ==="
for round in 1 2; do run_once "$CONTROL_ARM" "$CONTROL_LIMIT" "$round"; done
if ! grep -q ",$CONTROL_LIMIT,.*,true\|,$CONTROL_LIMIT,.*died-before-serving" "$OUT/results.csv"; then
  echo "VOID: nothing died at $CONTROL_LIMIT, so this harness cannot detect a death." >&2
  echo "      Results are in $OUT/results.csv and must not be quoted." >&2
  exit 1
fi
echo "control died as it must"

echo "=== $ROUNDS rounds per arm at $LIMIT, interleaved ==="
mapfile -t ALL_ARMS < <(arm_names)
for round in $(seq 1 "$ROUNDS"); do
  for i in $(seq 0 $(( ${#ALL_ARMS[@]} - 1 ))); do
    run_once "${ALL_ARMS[$(( (round - 1 + i) % ${#ALL_ARMS[@]} ))]}" "$LIMIT" "$round"
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
