#!/usr/bin/env bash
# The gate that stands in front of every measurement: do both arms do the same work?
#
# A three-column table is a claim that they do, and nothing enforces it by itself — the failure is
# invisible, because both numbers are real. An arm that answers `200` and stores nothing is fast for
# a reason that has nothing to do with the platform.
#
#   bench/parity.sh                      # every scheme, both arms
#   TWIN_BREAK=drop-insert bench/parity.sh   # the control: MUST fail
#
# Exit 0 only when every case agrees on the status **and** on what reached the database. Exit 1 on
# the first difference, printed. Exit 2 when the harness could not run the subjects at all.
set -uo pipefail

KOTLIN_IMAGE=${KOTLIN_IMAGE:-xyk:scratch}
GO_IMAGE=${GO_IMAGE:-xyk-twin:dev}
KOTLIN_PORT=${KOTLIN_PORT:-8071}
GO_PORT=${GO_PORT:-8072}
TWIN_BREAK=${TWIN_BREAK:-}
SECRET=parity-secret
ENDPOINT=hook-1
WORK=$(mktemp -d)

cleanup() { docker rm -f parity-kotlin parity-go >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

fail() { echo "parity: $*" >&2; exit 1; }

start() {
  local scheme=$1 config=${2:-}
  docker rm -f parity-kotlin parity-go >/dev/null 2>&1
  local env_common=(
    -e XYK_BOOTSTRAP_ENDPOINT_ID="$ENDPOINT"
    -e XYK_BOOTSTRAP_SECRET="$SECRET"
    -e XYK_BOOTSTRAP_SCHEME="$scheme"
    -e XYK_BOOTSTRAP_SCHEME_CONFIG="$config"
    -e XYK_BOOTSTRAP_SUBSCRIBERS="https://sink.invalid/a,https://sink.invalid/b"
  )
  docker run -d --name parity-kotlin -p "$KOTLIN_PORT:8080" "${env_common[@]}" "$KOTLIN_IMAGE" >/dev/null \
    || { echo "parity: could not start $KOTLIN_IMAGE" >&2; exit 2; }
  docker run -d --name parity-go -p "$GO_PORT:8080" "${env_common[@]}" \
    -e TWIN_BREAK="$TWIN_BREAK" "$GO_IMAGE" >/dev/null \
    || { echo "parity: could not start $GO_IMAGE" >&2; exit 2; }

  local ready=no
  for _ in $(seq 1 60); do
    sleep 0.5
    if curl -sf -o /dev/null "http://127.0.0.1:$KOTLIN_PORT/health/ready" \
       && curl -sf -o /dev/null "http://127.0.0.1:$GO_PORT/health/ready"; then ready=yes; break; fi
  done
  [ "$ready" = yes ] || { echo "parity: an arm never became ready" >&2; exit 2; }
}

# One case against both arms. Any difference in status is a difference in behaviour.
both() {
  local name=$1; shift
  local kotlin go
  kotlin=$(curl -s -o /dev/null -w '%{http_code}' "$@" --url "http://127.0.0.1:$KOTLIN_PORT$PATH_SUFFIX")
  go=$(curl -s -o /dev/null -w '%{http_code}' "$@" --url "http://127.0.0.1:$GO_PORT$PATH_SUFFIX")
  printf '  %-34s kotlin=%s go=%s\n' "$name" "$kotlin" "$go"
  [ "$kotlin" = "$go" ] || fail "$name: kotlin answered $kotlin and go answered $go"
}

hmac_hex() { printf %s "$2" | openssl dgst -sha256 -hmac "$1" -hex | sed 's/.*= //'; }

# What reached the database, projected so that the parts which are allowed to differ — random ids,
# timestamps — are left out, and the parts that are not are compared exactly.
#
# The journal is copied with the database: in WAL mode the main file is only the checkpointed part,
# and a comparison of two half-copied databases agrees about nothing at all (research 1.15).
stored() {
  local container=$1 out=$2
  rm -f "$WORK/$container.db"*
  for file in xyk.db xyk.db-wal xyk.db-shm; do
    docker cp "$container:/data/$file" "$WORK/$container.db${file#xyk.db}" >/dev/null 2>&1
  done
  sqlite3 "$WORK/$container.db" \
    "SELECT scheme, body_bytes, secret_fingerprint, hex(body) FROM events ORDER BY hex(body), body_bytes;" \
    > "$out" 2>/dev/null
  sqlite3 "$WORK/$container.db" "SELECT count(*) FROM deliveries;" >> "$out" 2>/dev/null
}

compare_stored() {
  stored parity-kotlin "$WORK/kotlin.rows"
  stored parity-go "$WORK/go.rows"
  if ! diff -q "$WORK/kotlin.rows" "$WORK/go.rows" >/dev/null; then
    echo "parity: the arms stored different things —" >&2
    diff "$WORK/kotlin.rows" "$WORK/go.rows" | head -10 >&2
    exit 1
  fi
  printf '  %-34s %s rows agree\n' "stored state" "$(wc -l < "$WORK/kotlin.rows")"
}

echo "parity: $KOTLIN_IMAGE vs $GO_IMAGE${TWIN_BREAK:+  (TWIN_BREAK=$TWIN_BREAK)}"

# ---------------------------------------------------------------- github
echo "github:"
start github
BODY='{"zen":"Half measures are as bad as nothing at all."}'
SIG=$(hmac_hex "$SECRET" "$BODY")
PATH_SUFFIX="/hooks/$ENDPOINT"
both "genuine"            -X POST -H "X-Hub-Signature-256: sha256=$SIG" --data-binary "$BODY"
both "forged (body + 1 byte)" -X POST -H "X-Hub-Signature-256: sha256=$SIG" --data-binary "${BODY}x"
both "wrong prefix"       -X POST -H "X-Hub-Signature-256: sha512=$SIG" --data-binary "$BODY"
both "no header"          -X POST --data-binary "$BODY"
PATH_SUFFIX="/hooks/no-such-endpoint"
both "unknown endpoint"   -X POST -H "X-Hub-Signature-256: sha256=$SIG" --data-binary "$BODY"
PATH_SUFFIX="/hooks/$ENDPOINT"
head -c 2000000 /dev/urandom > "$WORK/big.bin"
both "oversized"          -X POST -H "X-Hub-Signature-256: sha256=$SIG" --data-binary "@$WORK/big.bin"
compare_stored

# ---------------------------------------------------------------- stripe
echo "stripe:"
start stripe
NOW=$(date +%s); OLD=$((NOW - 1000))
V1=$(hmac_hex "$SECRET" "$NOW.$BODY")
V1OLD=$(hmac_hex "$SECRET" "$OLD.$BODY")
both "fresh"              -X POST -H "Stripe-Signature: t=$NOW,v1=$V1" --data-binary "$BODY"
both "stale timestamp"    -X POST -H "Stripe-Signature: t=$OLD,v1=$V1OLD" --data-binary "$BODY"
both "v0 only"            -X POST -H "Stripe-Signature: t=$NOW,v0=$V1" --data-binary "$BODY"
both "no header"          -X POST --data-binary "$BODY"
compare_stored

# ---------------------------------------------------------------- telegram
echo "telegram:"
start telegram
both "right token"        -X POST -H "X-Telegram-Bot-Api-Secret-Token: $SECRET" --data-binary "$BODY"
both "wrong token"        -X POST -H "X-Telegram-Bot-Api-Secret-Token: nope" --data-binary "$BODY"
both "no header"          -X POST --data-binary "$BODY"
compare_stored

# ---------------------------------------------------------------- generic
echo "hmac-sha256:"
start hmac-sha256 '{"header":"X-Prov-Sig","encoding":"base64"}'
B64=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -binary | base64)
both "base64 digest"      -X POST -H "X-Prov-Sig: $B64" --data-binary "$BODY"
both "hex where base64 is expected" -X POST -H "X-Prov-Sig: $SIG" --data-binary "$BODY"
both "wrong header"       -X POST -H "X-Other: $B64" --data-binary "$BODY"
compare_stored

# ---------------------------------------------------------------- under load
#
# PARITY UNDER LOAD IS A DIFFERENT CLAIM, and this case exists because the gate missed a real defect
# without it: the twin returned `500` for half its requests at 2 000 rps because its driver answered
# SQLITE_BUSY and nothing waited for the writer lock. One request at a time, both arms agreed
# perfectly. A twin that sheds load is not doing the same work — it is being fast by doing less.
echo "concurrent burst:"
start github
burst() {
  local port=$1 name=$2 failures=0
  for _ in $(seq 1 40); do
    curl -s -o /dev/null -w '%{http_code}\n' -X POST "http://127.0.0.1:$port/hooks/$ENDPOINT" \
      -H "X-Hub-Signature-256: sha256=$SIG" --data-binary "$BODY" &
  done > "$WORK/$name.codes"
  wait
  # `grep -c` prints 0 and exits 1 when it matches nothing, so the exit code is swallowed and the
  # count is taken from stdout. An `|| echo 0` here would print the number twice.
  failures=$(grep -vc '^200$' "$WORK/$name.codes" 2>/dev/null)
  printf '  %-34s %s of 40 not 200\n' "$name" "$failures"
  [ "$failures" = "0" ] || fail "$name shed $failures requests out of 40 concurrent ones"
}
SIG=$(hmac_hex "$SECRET" "$BODY")
burst "$KOTLIN_PORT" kotlin
burst "$GO_PORT" go

echo "parity: every case agrees, on the answer and on what was stored"
