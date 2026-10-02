# S0 on the box: fresh clone, two arms through the shipping Dockerfile, the bare re-import, digests.
set -uo pipefail
COMMIT=$1
cd ~ && rm -rf xyk-g1 && git clone -q https://github.com/youndie/xyk xyk-g1 && cd xyk-g1
git fetch -q origin chore/g1-arena-allocator-verdict && git checkout -q "$COMMIT"
echo "# S0 build $(date -Is) host=$(hostname) kernel=$(uname -r) commit=$(git rev-parse HEAD)"
for alloc in paged-off default; do
  echo "=== build $alloc ==="
  docker build -f docker/scratch.Dockerfile --build-arg XYK_HTTP_CLIENT=true \
    --build-arg XYK_ALLOCATOR=$alloc --build-arg XYK_GRADLE_FLAGS=--max-workers=2 \
    -t xyk-g1:$alloc-ship . > ~/xyk-g1-build-$alloc.log 2>&1
  echo "build $alloc rc=$?"; grep -E "xyk build:|BUILD SUCCESSFUL|BUILD FAILED|error" ~/xyk-g1-build-$alloc.log | head -5
done
for alloc in paged-off default; do
  img=xyk-g1:$alloc-ship
  docker image inspect "$img" >/dev/null 2>&1 || { echo "no image $img"; continue; }
  envs=$(docker image inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$img" | grep -v '^MALLOC_ARENA_MAX=' | grep .)
  echo "$img env: $(docker image inspect -f '{{json .Config.Env}}' "$img") entrypoint: $(docker image inspect -f '{{json .Config.Entrypoint}}' "$img") workdir: $(docker image inspect -f '{{.Config.WorkingDir}}' "$img")"
  changes=(--change "WORKDIR $(docker image inspect -f '{{.Config.WorkingDir}}' "$img")" --change 'VOLUME ["/data"]' --change 'EXPOSE 8080' --change "ENTRYPOINT $(docker image inspect -f '{{json .Config.Entrypoint}}' "$img")")
  while read -r e; do changes+=(--change "ENV $e"); done <<<"$envs"
  cid=$(docker create "$img")
  docker export "$cid" | docker import "${changes[@]}" - xyk-g1:$alloc >/dev/null
  docker cp "$cid":/app/server /tmp/xyk-g1-$alloc.bin && echo "binary $alloc sha256=$(sha256sum /tmp/xyk-g1-$alloc.bin | cut -d' ' -f1) bytes=$(stat -c %s /tmp/xyk-g1-$alloc.bin)"
  docker rm -v "$cid" >/dev/null
  echo "bare xyk-g1:$alloc env: $(docker image inspect -f '{{json .Config.Env}}' xyk-g1:$alloc) entrypoint: $(docker image inspect -f '{{json .Config.Entrypoint}}' xyk-g1:$alloc)"
  # the re-imported filesystem must hold the same binary
  cid=$(docker create xyk-g1:$alloc); docker cp "$cid":/app/server /tmp/xyk-g1-$alloc.bare.bin; docker rm -v "$cid" >/dev/null
  echo "bare binary $alloc sha256=$(sha256sum /tmp/xyk-g1-$alloc.bare.bin | cut -d' ' -f1)"
  rm -f /tmp/xyk-g1-$alloc.bin /tmp/xyk-g1-$alloc.bare.bin
done
echo "S0 build done"
