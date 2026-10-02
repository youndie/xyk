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
#
# The documentation half is docs-bootstrap's templates/Makefile, copied to the root of the
# repository next to .github/workflows/check.yaml copied from templates/workflow-check.yaml.
#
#   make check    the gate and the reports - exactly what CI runs
#   make fix      regenerate the backlog index, append missing coverage-map lines
#
# ONE VERSION OF THE CHECKS, WRITTEN DOWN ONCE: the `uses: youndie/docs-bootstrap@<ref>` line in
# .github/workflows/check.yaml. CI runs the checks at that ref because the runner resolves the line.
# This file reads the same line and fetches the same ref into .docs-bootstrap/, a directory that
# ignores itself, so `make check` here runs what CI runs - the same scripts, the same guard, the same
# flags. Renovate bumps the line, and the next `make check` fetches what CI already moved to.
#
# WHY THE SCRIPTS ARE NOT COPIED IN. A copied check runs, but at the version of the day it was copied,
# and a fix upstream never arrives: across one portfolio 18 copies of backlog_index.py were found in
# three versions, eleven of them without the guard that makes `--check` fail when the backlog has
# gone missing - a guard that existed upstream the whole time.
#
# WHY THE VERSION IS NOT ALSO WRITTEN HERE. A version pinned in the workflow and again in this file is
# two pins, and two pins drift: one is bumped, the other is found months later, and "green here, red
# there" comes back with nobody able to say which side is right. So this file holds none; if the
# workflow names two different refs, it refuses to choose.
#
# WHAT LIVES HERE is what is this repository's own: where the tree is, how the backlog is kept, and
# checks of its own under `gate`. How the documents are checked - including the guard that fails the
# gate when docs/ or the backlog is not there - is in check.mk at the pinned version, and changes
# arrive with a bump instead of with a re-copy.
#
# ONLY A GOAL THAT RUNS THE CHECKS LOADS THEM. A project adds targets of its own below this head - a
# chart, a stand, a release - and make reads every included file, fetching the ones that are
# missing, before it runs any goal at all. Included unconditionally, check.mk made each of those
# targets, `make` alone and even `make -n` read the pin and download it on a fresh clone, and fail
# offline. So it is included only when a goal asked for - on the command line, or the default goal
# when there is none - is in DOCS_BOOTSTRAP_GOALS or is one of check.mk's own `docs-` targets; every
# other goal runs without docs-bootstrap and without the network. A goal of the project's own that
# leads to the checks (`ci: check build`) is added to DOCS_BOOTSTRAP_GOALS, above the line that says
# nothing below is meant to be edited; one that is not added stops on a message naming that
# variable.
#
# OVERRIDES. `DOCS_BOOTSTRAP=<dir>` runs the checks from a directory instead of the pinned ref: a
# clone of docs-bootstrap you are changing, or - offline, or without GitHub Actions - a committed
# copy of its check.mk, scripts/ and .claude-plugin/. That last one is the copy route again, with its
# drift; it is the fallback, not the default.

DOCS ?= docs
BACKLOG ?= backlog.md
# How the backlog is kept (docs-bootstrap SKILL.md, step 7): `files` - one file per item in
# $(DOCS)/backlog/ and the generated index in $(BACKLOG); `milestones` - one hand-kept file at
# $(BACKLOG), usually BACKLOG.md; `none` - no backlog, yet.
BACKLOG_FORM ?= files
# A directory whose subdirectories are the repositories the code anchors point into. `..` is the
# directory this clone sits in - in CI, a directory holding this clone and nothing else; on a laptop,
# its siblings too, which a suffix match can mistake for this repository.
REPOS ?= ..
PY ?= python3

# Where the pin is, and what it names.
DOCS_BOOTSTRAP_PIN ?= .github/workflows/check.yaml
DOCS_BOOTSTRAP_REPO ?= youndie/docs-bootstrap
DOCS_BOOTSTRAP_CACHE ?= .docs-bootstrap
# The revision of this file. check.mk says so when a newer docs-bootstrap expects a newer one.
DOCS_BOOTSTRAP_SHIM := 2

# The goals that load the checks - and so read the pin and, on a fresh clone, fetch it. check.mk's
# `docs-` targets load them by themselves. A goal of this repository's own that runs one of these
# goes here too, e.g. for `ci: check build`:
#	DOCS_BOOTSTRAP_GOALS += ci
DOCS_BOOTSTRAP_GOALS := check gate report fix
# `make docs` is this repository's name for the documentation half (`docs: docs-gate`), and `gate`
# reaches the checks through it.
DOCS_BOOTSTRAP_GOALS += docs

.DEFAULT_GOAL := help
.PHONY: check docs gate report build image image-scratch twin parity fix help

help:
	@echo "make check   - the gate and the reports: documents + ./gradlew check. Exactly what CI runs"
	@echo "make gate    - the blocking half alone"
	@echo "make docs    - the documentation half of the gate only; runs anywhere"
	@echo "make build   - link, image, stop order, a rendered page and no secret out of the image (needs docker)"
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

# The documentation half, at the version the workflow pins: the guard, the backlog index, the
# documents, the coverage map. Plus the one check of this repository's own: a pre-registration whose
# bytes were recorded before its measurement still has those bytes (B-32). Its history half
# (`--history --window`) is not here: squash merges keep no branch commits, so it runs on the branch
# before the merge and its output is kept with the raw runs.
docs: docs-gate
	python3 scripts/brief_freeze.py --brief docs/backlog/B-32-arena-cap-on-paged-off.md

# NOT part of `make check`, and this is the one place that rule is bent, so the reason is here rather
# than assumed: it links a release binary (a minute of LLVM) and needs a working docker, which a
# contributor editing a document does not have to have. CI runs it as a job of its own, so it cannot
# rot unseen.
#
# The stop order is asserted rather than printed. `EmbeddedServer.stop` runs its steps in the
# opposite order on Kotlin/Native and on the JVM from identical source, and the failure that produces
# here is a webhook answered `200` and never delivered.
#
# The smoke test runs twice. The second run is its positive control for the secret search: it puts a
# secret where the pages render it by design, and must exit 3 — any other code, including a pass, is a
# search that would not have seen a leak. Exactly 3, because a run that died for another reason also
# "fails", and that is not the failure being asked for. Its other control — an image without charset
# converters, which must exit 4 — is in `image-scratch`, because that image is linked inside docker.
build: image
	dev/shutdown-check.sh xyk:dev
	dev/image-smoke.sh xyk:dev
	@echo "--- the leak control must exit 3 ---"
	@SECRET_IN_DESCRIPTION=1 dev/image-smoke.sh xyk:dev >/dev/null 2>&1; rc=$$?; \
	if [ $$rc -eq 3 ]; then echo "leak control exited 3 as it must: the search finds a secret that is in a response"; \
	else echo "LEAK CONTROL exited $$rc, not 3: the secret search cannot see a secret it was shown" >&2; exit 1; fi

image:
	./gradlew :server:linkReleaseExecutableNative
	dev/binary-report.sh
	docker build -f docker/native.Dockerfile -t xyk:dev .
	@echo "image pull bytes: $$(docker save xyk:dev | wc -c)  (docker save on this host; 'docker image inspect .Size' means different things on different storage drivers)"

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

# The `scratch` image, kept out of `make build` because it links the binary INSIDE docker — minutes
# rather than the second the assembly takes. The control is the point of the second half: an image
# with everything except the charset converters must fail the smoke test, or the smoke test has
# never been shown able to see what it was written for.
#
# It must fail with EXACTLY 4 — "a body in another charset was not decoded" — and not merely fail.
# That image renders every page (research §1.14), so a 1 would be some other breakage, and a 2 a
# harness that never reached the subject; neither shows the smoke test seeing missing converters.
# Until 2026-10-02 the smoke test had no step that reached a converter, the control passed, and this
# target — and `parity` behind it — failed on every run.
image-scratch:
	docker build -f docker/scratch.Dockerfile -t xyk:scratch .
	docker build -f docker/scratch-control.Dockerfile -t xyk:scratch-nogconv .
	@echo "scratch image: $$(docker save xyk:scratch | wc -c) pull bytes"
	dev/shutdown-check.sh xyk:scratch
	dev/image-smoke.sh xyk:scratch
	@echo "--- the gconv control must exit 4 ---"
	@dev/image-smoke.sh xyk:scratch-nogconv >/dev/null 2>&1; rc=$$?; \
	if [ $$rc -eq 4 ]; then echo "gconv control exited 4 as it must: the smoke test sees an image without its charset converters"; \
	else echo "GCONV CONTROL exited $$rc, not 4: the smoke test cannot see missing charset converters" >&2; exit 1; fi

# Non-blocking, on purpose. bdd_report counts scenarios, and some are manual by design — a criterion
# measured by a harness on two machines, a crash between two statements — so a percentage is not a
# gate. code_anchors cannot tell a live path from one quoted as obsolete — and what it reports here
# are addresses inside other repositories and artefacts, where a research fact was verified, not
# paths in this tree.
report: docs-report

fix: docs-fix

# -- where the checks come from. Nothing below is meant to be edited. ------------------------------

# The goals this run was asked for: the command line's, or the default goal when it names none.
DOCS_BOOTSTRAP_ASKED := $(or $(MAKECMDGOALS),$(.DEFAULT_GOAL))

ifneq ($(filter $(DOCS_BOOTSTRAP_GOALS) docs-%,$(DOCS_BOOTSTRAP_ASKED)),)

ifndef DOCS_BOOTSTRAP
DOCS_BOOTSTRAP_REF := $(sort $(shell sed -n -E 's|^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]*"?$(DOCS_BOOTSTRAP_REPO)@([^"[:space:]]+).*|\2|p' $(DOCS_BOOTSTRAP_PIN) 2>/dev/null))
ifeq ($(words $(DOCS_BOOTSTRAP_REF)),0)
$(error no `uses: $(DOCS_BOOTSTRAP_REPO)@<ref>` in $(DOCS_BOOTSTRAP_PIN). That line is the version of the checks, for CI and for this file alike - copy templates/workflow-check.yaml, or run with DOCS_BOOTSTRAP=<a local copy>)
endif
ifneq ($(words $(DOCS_BOOTSTRAP_REF)),1)
$(error $(DOCS_BOOTSTRAP_PIN) pins $(DOCS_BOOTSTRAP_REPO) at more than one ref: $(DOCS_BOOTSTRAP_REF). One version of the checks, one ref - make every uses: line name the same one)
endif
DOCS_BOOTSTRAP := $(DOCS_BOOTSTRAP_CACHE)/$(DOCS_BOOTSTRAP_REF)
else ifeq ($(wildcard $(DOCS_BOOTSTRAP)/check.mk),)
$(error DOCS_BOOTSTRAP=$(DOCS_BOOTSTRAP) holds no check.mk)
endif

include $(DOCS_BOOTSTRAP)/check.mk

else

# Not loaded, so no `docs-` target exists in this run. A goal that reaches one anyway is missing from
# DOCS_BOOTSTRAP_GOALS, and make's own "No rule to make target" would not say so.
docs-%:
	@echo "$@ is a target of docs-bootstrap's check.mk, which this run did not load: '$(DOCS_BOOTSTRAP_ASKED)' is not in DOCS_BOOTSTRAP_GOALS ($(strip $(DOCS_BOOTSTRAP_GOALS))). Add the goal that leads to $@ to DOCS_BOOTSTRAP_GOALS in the Makefile." >&2; exit 2

endif

# The fetch. A tarball of the ref rather than a clone: a tag, a branch and a commit SHA (what
# Renovate writes when it pins digests) are all one URL, and no history is needed. Unpacked next to
# its final place and moved in only once complete, so an interrupted fetch never leaves a directory
# that looks like a version. GNU make 3.81 - the one macOS ships - announces the missing file
# ("check.mk: No such file or directory") just before fetching it; that line is not the error.
$(DOCS_BOOTSTRAP_CACHE)/%/check.mk:
	@echo "docs-bootstrap: fetching $(DOCS_BOOTSTRAP_REPO)@$* - the ref $(DOCS_BOOTSTRAP_PIN) pins"
	@rm -rf "$(@D).part" && mkdir -p "$(@D).part"
	@curl -fsSL --retry 2 -o "$(@D).part/src.tar.gz" "https://codeload.github.com/$(DOCS_BOOTSTRAP_REPO)/tar.gz/$*" || { rm -rf "$(@D).part"; echo "could not fetch $(DOCS_BOOTSTRAP_REPO)@$* - offline, or a ref that does not exist? DOCS_BOOTSTRAP=<dir> runs a local copy instead" >&2; exit 1; }
	@tar -xzf "$(@D).part/src.tar.gz" -C "$(@D).part" --strip-components=1 && rm -f "$(@D).part/src.tar.gz"
	@test -f "$(@D).part/check.mk" || { echo "$(DOCS_BOOTSTRAP_REPO)@$* has no check.mk - versions before 0.3.0 cannot be pinned this way" >&2; rm -rf "$(@D).part"; exit 1; }
	@rm -rf "$(@D)" && mv "$(@D).part" "$(@D)"
	@echo '*' > "$(DOCS_BOOTSTRAP_CACHE)/.gitignore"
