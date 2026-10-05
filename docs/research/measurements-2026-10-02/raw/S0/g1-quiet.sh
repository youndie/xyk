#!/usr/bin/env bash
# One quietness sample of the Linux box, as declared in B-32's brief. Prints QUIET or NOISY and why.
runner=$(gh api orgs/<the org owning the box runner>/actions/runners --jq '.runners[] | select(.name=="wsl-desktop") | "\(.status) busy=\(.busy)"' 2>&1)
box=$(ssh -o ConnectTimeout=8 -p 2222 youndie@127.0.0.1 'bash -s' <<'B'
load1=$(cut -d" " -f1 /proc/loadavg)
sample=$(top -bn2 -d3 -w 200 | awk '/^top -/{n++} n==2 && $1 ~ /^[0-9]+$/ {print $1, $9}')
rest=$(awk '{s+=$2} END{print s+0}' <<<"$sample")
heavy=""; builds=""
while read -r pid pc; do
  awk -v p="$pc" 'BEGIN{exit !(p > 10)}' || continue
  args=$(ps -o args= -p "$pid" 2>/dev/null | cut -c1-120)
  heavy="$heavy $pid:$pc%:${args%% *}"
  grep -Eq "GradleDaemon|GradleWrapperMain|gradle|kotlin|konan|buildkit|docker build|buildx" <<<"$args" && builds="$builds $pid:$pc%"
done <<<"$sample"
echo "load1=$load1 rest_cpu=${rest}% heavy=[$heavy] builds=[$builds]"
B
)
echo "$(date '+%F %T') runner=[$runner] $box"
case "$runner" in *busy=false*) r=ok ;; *) r=busy ;; esac
l=$(sed -n 's/.*load1=\([0-9.]*\).*/\1/p' <<<"$box"); c=$(sed -n 's/.*rest_cpu=\([0-9.]*\)%.*/\1/p' <<<"$box")
b=$(sed -n 's/.*builds=\[\(.*\)\].*/\1/p' <<<"$box")
if [ "$r" = ok ] && awk -v l="$l" -v c="$c" 'BEGIN{exit !(l < 3 && c < 100)}' && [ -z "${b// /}" ]; then echo QUIET; else echo NOISY; fi
