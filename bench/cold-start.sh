#!/usr/bin/env bash
# B-22: cold start to the first 200, on a k0s node, in three phases.
#
#   bench/cold-start.sh [--rounds 5]
#
# Orchestrated from the workstation; the subject is the k0s node (`bench-a`), where the containers
# run under the node's own containerd — the same runtime a pod would get. **The scheduler's part is
# deliberately outside this**: image pull policy, node selection and admission belong to the cluster
# and the item says so. What is measured is the node doing the work.
#
# THREE PHASES, because one number hides the interesting one. Elsewhere unpack was 0.27 s static
# against 1.29 s on a base image while `run` to first answer was 0.41 against 0.56 — the phases do
# not move together.
#
#   import   the image into the node's content store and snapshot it — the "unpack" phase
#   listen   from `ctr run` to the port accepting a connection
#   first200 from accepting to a successful POST /hooks with a genuine signature
#
# THE IMAGE IS ALREADY ON THE NODE and the pull is not in any of these numbers. Pulling bytes over
# somebody's uplink measures the link; the item declares that separately and in different units.
#
# THE CACHE IS CLEARED BETWEEN ROUNDS — the image is removed and re-imported — because a second run
# measures a warm snapshotter, which is a different and easier question.
set -uo pipefail

ROUNDS=5
SUBJECT=${SUBJECT:-bench-a}
SECRET=bench-secret
ENDPOINT=hook-1
OUT=${OUT:-docs/research/measurements-$(date +%Y-%m-%d)}

while [ $# -gt 0 ]; do
  case "$1" in
    --rounds) ROUNDS=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT/raw"

BODY='{"zen":"Non-blocking is better than blocking."}'
SIGNATURE=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/.*= //')

cat > /tmp/xyk-cold-round.sh <<'REMOTE'
#!/bin/bash
# $1 = arm (xyk|twin), $2 = round, $3 = signature, $4 = body
set -uo pipefail
K=/usr/local/bin/k0s
case "$1" in
  xyk)  TAR=/root/xyk-cold.tar;      REF=docker.io/library/xyk:cold;      PORT=8080 ;;
  twin) TAR=/root/xyk-twin-cold.tar; REF=docker.io/library/xyk-twin:cold; PORT=8080 ;;
esac
NAME="cold-$1-$2"

$K ctr task kill -s SIGKILL "$NAME" 2>/dev/null
$K ctr container rm "$NAME" 2>/dev/null
# CLEARED, so the round measures an unpack rather than a cache hit.
$K ctr images rm "$REF" >/dev/null 2>&1
rm -rf "/root/cold-data-$2"; mkdir -p "/root/cold-data-$2"

t0=$(date +%s.%N)
$K ctr images import "$TAR" >/dev/null 2>&1 || { echo "import-failed"; exit 1; }
t1=$(date +%s.%N)

$K ctr run -d --net-host \
  --env XYK_DB_PATH="/data/x.db" --env XYK_PORT="$PORT" \
  --env XYK_BOOTSTRAP_ENDPOINT_ID=hook-1 --env XYK_BOOTSTRAP_SECRET=bench-secret \
  --mount "type=bind,src=/root/cold-data-$2,dst=/data,options=rbind:rw" \
  "$REF" "$NAME" >/dev/null 2>&1 || { echo "run-failed"; exit 1; }

# Listening: the first TCP connection that is accepted. `curl --connect-timeout` would round to
# milliseconds we care about, so this is a tight loop on /dev/tcp.
t2=""
for _ in $(seq 1 20000); do
  if (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; then exec 3<&- 3>&-; t2=$(date +%s.%N); break; fi
done
[ -n "$t2" ] || { echo "never-listened"; exit 1; }

# The first 200 through the REAL route: a probe answers before the database has been touched.
t3=""
for _ in $(seq 1 2000); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -m 5 -X POST "http://127.0.0.1:$PORT/hooks/hook-1" \
    -H "Content-Type: application/json" -H "X-Hub-Signature-256: sha256=$3" -d "$4" 2>/dev/null)
  if [ "$code" = 200 ]; then t3=$(date +%s.%N); break; fi
done
[ -n "$t3" ] || { echo "never-answered"; exit 1; }

$K ctr task kill -s SIGKILL "$NAME" >/dev/null 2>&1
sleep 1
$K ctr container rm "$NAME" >/dev/null 2>&1
rm -rf "/root/cold-data-$2"

awk -v a="$t0" -v b="$t1" -v c="$t2" -v d="$t3" \
  'BEGIN { printf "%.3f,%.3f,%.3f,%.3f\n", b-a, c-b, d-c, d-b }'
REMOTE
scp -q /tmp/xyk-cold-round.sh "$SUBJECT:/tmp/"

echo "arm,round,import_s,listen_s,first200_s,run_to_200_s" > "$OUT/cold-start.csv"
echo "=== $ROUNDS rounds, arms interleaved, image cache cleared before each ==="
for round in $(seq 1 "$ROUNDS"); do
  for arm in xyk twin; do
    line=$(ssh "$SUBJECT" "bash /tmp/xyk-cold-round.sh $arm $round '$SIGNATURE' '$BODY'")
    case "$line" in
      *,*) printf '%s,%s,%s\n' "$arm" "$round" "$line" >> "$OUT/cold-start.csv"
           echo "  round $round $arm: import/listen/first200 = $line" ;;
      *)   printf '%s,%s,%s,,,\n' "$arm" "$round" "$line" >> "$OUT/cold-start.csv"
           echo "  round $round $arm: $line" >&2 ;;
    esac
  done
done

echo
column -t -s, "$OUT/cold-start.csv"
