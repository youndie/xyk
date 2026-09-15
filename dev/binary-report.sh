#!/usr/bin/env bash
# What the binary asks the loader for, and how big it is.
#
# **Deliberately not a gate.** A new shared dependency — `libz` arriving with the curl engine is the
# likely one — has to be visible in the log of the build that introduced it, not in a container that
# fails to start three weeks later. A threshold here would instead be a number somebody raises.
#
#   dev/binary-report.sh [path-to-binary]
set -uo pipefail

BIN=${1:-server/build/bin/native/releaseExecutable/server.kexe}
[ -f "$BIN" ] || { echo "binary-report: no binary at $BIN" >&2; exit 2; }

echo "binary:  $BIN"
echo "bytes:   $(stat -c %s "$BIN")"

if command -v readelf >/dev/null; then
  needed=$(readelf -d "$BIN" 2>/dev/null | grep -oE 'Shared library: \[[^]]+\]' | sed 's/.*\[\(.*\)\]/\1/' | tr '\n' ' ')
  echo "needs:   ${needed:-(nothing — no dynamic section)}"
  # The runtime image has to carry every one of these. distroless/cc carries the usual set; `libz`
  # is the one that arrives with the HTTP client and is NOT in a plain distroless/base.
else
  # Said out loud rather than skipped: there is no readelf on a Mac, and an absent answer must not
  # read as "nothing needed".
  echo "needs:   (unknown — no readelf on this host)"
fi
