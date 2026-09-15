# One gate, and CI runs exactly this target.
#
# A local check set that differs from the CI one turns "green here, red there" into the normal state
# of affairs, and then neither is read. So: whatever is not in `make check` is not a gate, and
# whatever is in it runs the same way in both places.
#
# `make check` runs the code as well as the documents, deliberately. Two gates mean two things to
# remember, and the one that is not `make check` is the one that stops being run.
#
# WHERE IT RUNS. The Gradle half needs the Linux box — a Kotlin/Native link is minutes of LLVM and
# this project is synchronised there. `make docs` is the half that runs anywhere, for an agent
# editing a document on the Mac.

PY ?= python3

.PHONY: check docs gate report build image image-scratch twin parity fix help

help:
	@echo "make check   - the gate: documents + ./gradlew check. Exactly what CI runs"
	@echo "make docs    - the documentation half only; runs anywhere"
	@echo "make build   - link, image, stop order, and a rendered page out of the image (needs docker)"
	@echo "make image-scratch - the FROM scratch image, its size, and the gconv control (minutes)"
	@echo "make twin    - the Go twin's image, for the second column"
	@echo "make parity  - rebuild both arms and refuse to measure until they agree"
	@echo "make report  - non-blocking reports: BDD coverage, code anchors"
	@echo "make fix     - regenerate the backlog index, fill in missing coverage-map lines"

check: gate report

# Blocking. A failure here means the documentation is internally inconsistent — a defect in the
# documentation rather than a matter of opinion — or the code does not compile and lint, which is
# not a matter of opinion either.
gate: docs
	./gradlew check

docs:
	$(PY) scripts/backlog_index.py --check
	$(PY) scripts/docs_check.py
	$(PY) scripts/coverage_map.py --check

# NOT part of `make check`, and this is the one place that rule is bent, so the reason is here rather
# than assumed: it links a release binary (a minute of LLVM) and needs a working docker, which a
# contributor editing a document does not have to have. CI runs it as a job of its own, so it cannot
# rot unseen.
#
# The stop order is asserted rather than printed. `EmbeddedServer.stop` runs its steps in the
# opposite order on Kotlin/Native and on the JVM from identical source, and the failure that produces
# here is a webhook answered `200` and never delivered.
build: image
	dev/shutdown-check.sh xyk:dev
	dev/image-smoke.sh xyk:dev

image:
	./gradlew :server:linkReleaseExecutableNative
	dev/binary-report.sh
	docker build -f docker/native.Dockerfile -t xyk:dev .
	@echo "image pull bytes: $$(docker save xyk:dev | wc -c)  (docker save on this host; `docker image inspect .Size` means different things on different storage drivers)"

# The `scratch` image, kept out of `make build` because it links the binary INSIDE docker — minutes
# rather than the second the assembly takes. The control is the point of the second half: an image
# with everything except the charset converters must fail the smoke test, or the smoke test has
# never been shown able to see what it was written for.
# The second column. Built the same way the subject is — static, no base image — because a cgo build
# would need one and the comparison would then be measuring packaging.
twin:
	cd twin-go && docker build -t xyk-twin:dev .
	@echo "twin image: $$(docker save xyk-twin:dev | wc -c) pull bytes"

# BOTH IMAGES ARE REBUILT HERE, and that is not tidiness. The gate compares images, an image is a
# snapshot, and running it against whatever was built last time compares yesterday's code — which
# happened on the first run of this gate and looked exactly like a passing check.
parity: image-scratch twin
	bench/parity.sh

image-scratch:
	docker build -f docker/scratch.Dockerfile -t xyk:scratch .
	docker build -f docker/scratch-control.Dockerfile -t xyk:scratch-nogconv .
	@echo "scratch image: $$(docker save xyk:scratch | wc -c) pull bytes"
	dev/shutdown-check.sh xyk:scratch
	dev/image-smoke.sh xyk:scratch
	@echo "--- the control must FAIL ---"
	@if dev/image-smoke.sh xyk:scratch-nogconv >/dev/null 2>&1; then 		echo "CONTROL PASSED, which means the smoke test cannot see a missing gconv" >&2; exit 1; 	else 		echo "control failed as it must: the smoke test can see an image that does not render"; 	fi

# Non-blocking, on purpose. bdd_report counts scenarios, and demanding a percentage is meaningless
# while every scenario is a target and acceptance is done by hand. code_anchors cannot tell a live
# path from one quoted as obsolete — and here it reports the paths the backlog has not created yet.
report:
	$(PY) scripts/bdd_report.py
	$(PY) scripts/code_anchors.py --repos ..

fix:
	$(PY) scripts/backlog_index.py
	$(PY) scripts/coverage_map.py --fix
