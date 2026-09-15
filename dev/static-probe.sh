#!/usr/bin/env bash
# B-05: what does the outbound HTTP engine cost a static link, and does it refuse one outright?
#
# Four builds of the same source — engine absent or present, linked dynamically or statically — with
# the size and the dynamic-section of each. The brief's kill condition is decided by row 2: if the
# INCOMING half alone cannot be linked statically, the platform layer has named its boundary and that
# is the result.
#
#   dev/static-probe.sh            # all four, on this host
#
# It runs where the link runs: the Linux box. A static link needs `libc.a`, `crt1.o`, `libstdc++.a`
# and — with the engine — `libz.a`; `apt-get install g++ zlib1g-dev` supplies them, and their absence
# reads as `unable to find library -lc`, which looks like a linker-flag problem and is not one.
set -uo pipefail

BIN=server/build/bin/native/releaseExecutable/server.kexe
OUT=${OUT:-build/link-probe}
mkdir -p "$OUT"

echo "host: $(uname -srm), glibc $(ldd --version | head -1 | grep -oE '[0-9]+\.[0-9]+$')"
echo

printf '%-28s %-8s %14s  %s\n' variant link bytes dynamic-section
printf '%-28s %-8s %14s  %s\n' ---------------------------- -------- -------------- ---------------

for client in false true; do
  for static in false true; do
    ./gradlew :server:linkReleaseExecutableNative \
      -Pxyk.httpClient=$client -Pxyk.staticLink=$static \
      --console=plain > "$OUT/build-$client-$static.log" 2>&1
    rc=$?

    label=$([ "$client" = true ] && echo "server + ktor-client-curl" || echo "server only")
    mode=$([ "$static" = true ] && echo static || echo dynamic)

    if [ $rc -ne 0 ]; then
      printf '%-28s %-8s %14s  %s\n' "$label" "$mode" "LINK FAILED" "see $OUT/build-$client-$static.log"
      continue
    fi

    bytes=$(stat -c %s "$BIN")
    # What the binary asks the loader for. A static one asks for nothing, which is the whole claim;
    # a dynamic one names every library the image has to carry.
    needed=$(readelf -d "$BIN" 2>/dev/null | grep -oE 'Shared library: \[[^]]+\]' | sed 's/.*\[\(.*\)\]/\1/' | tr '\n' ' ')
    [ -z "$needed" ] && needed="(none — no dynamic section)"
    cp "$BIN" "$OUT/server-client-$client-static-$static.kexe"
    printf '%-28s %-8s %14s  %s\n' "$label" "$mode" "$bytes" "$needed"
  done
done

echo
echo "binaries kept in $OUT/ for the image work (B-17, B-18)"
