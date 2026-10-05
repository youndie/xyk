#!/usr/bin/env python3
"""Check that a pre-registration has not moved since it was frozen.

A pre-registration is worth having only because its text cannot change after the numbers come in.
"We did not edit it" is a promise; a digest checked on every run is a fact that anybody holding the
repository can verify. This is one generic copy of a check that three studies carried as three
diverged scripts, each with its own digest, size and path written into the code.

What it guards, and how:

* **The bytes of the frozen file, not its text.** A reflowed paragraph, converted line endings, a
  ticked checkbox or one trailing newline all fail. The digest and the size live on a record line in
  BRIEF.md, so the script holds nothing that belongs to one study:

      <!-- frozen: path=docs/research/source-brief.md bytes=18849 sha256=<64 hex> scope=after-marker -->

  Each of the four keys exactly once and no other; one record per path; a path inside the
  repository; a frozen text that is not empty. Anything else is refused — a check over nothing, or
  over the wrong one of two values, would pass whatever happened. `scope=after-marker` hashes only
  what follows MARKER, so a documentation wrapper above it stays editable while the frozen text does
  not; `scope=whole` hashes the whole file. `--record PATH` prints the line for a file frozen now.

* **The record and the bytes across history.** A digest kept in the repository can be edited in the
  same commit as the text it guards, and the plain check then passes; so `make check` runs
  `--history` too. It fails when a commit descending from the record's first appearance carries a
  different record for that path, none at all, no frozen file, or frozen bytes that do not match —
  an edit later reverted included. It reads side branches (`--full-history`): by default git drops
  the commits of a merged branch whose result equals the main line, so a threshold moved on a
  branch, measured and moved back before a `--no-ff` merge would pass. **A squash merge keeps
  nothing of the branch's commits**, and no history check can see an edit made and undone inside a
  squashed branch: merge study branches, or run `--history` on the branch before squashing it.
  With `--window DIR`, every commit that added a measurement under DIR (a README excepted) must
  descend from the freeze and be a different commit: a freeze made together with the first number
  cannot show that it came first. A shallow clone and a missing DIR are refused, not passed.

* **A study that adopts the record after it began.** Its frozen file was committed, and held by some
  other means, before this line existed, so the record's first appearance comes after every
  measurement and `--window` could only fail. An optional fifth key, `since=<full commit id>`, names
  the commit from which the recorded bytes have stood; `--record PATH --since REV` writes it and
  refuses a commit whose bytes differ. `--history` then checks the bytes in that commit and in every
  later one that held the file, and closes the window there: a study that froze before it measured
  can show it, and one that did not is told so. The anchor must be an ancestor of the commit that
  first recorded it, and, like the rest of the record, it can never be moved afterwards.

* **Its own ability to fail.** `--control` builds small trees that must fail each rule and must pass
  the legitimate edits, then mutates this repository's own frozen files in memory. One of the three
  copies carried a control case whose mutation searched for a heading its wrapper did not contain:
  the "edited" file was the committed file, and the case passed for a reason unrelated to its name
  (pgo-native-spike, scripts/brief_freeze.py, "wrapper prose edited"). Here every mutation is
  positional, a case whose mutation changed nothing fails the control, and every case that must fail
  names the error it must fail with — a case turned red by a neighbouring rule would stay red with
  its own rule switched off. Each rule was switched off once in a copy and its case went red; the
  byte count is the exception, implied by the digest and kept for the reader.

    brief_freeze.py                            check every record in BRIEF.md
    brief_freeze.py --history --window logs    the record and the bytes never moved, frozen before logs/
    brief_freeze.py --control                  prove that each rule fires
    brief_freeze.py --record PATH [--whole] [--since REV]
                                               print the record line to paste into BRIEF.md

`make check` runs the first three; CI needs the whole history (`fetch-depth: 0`). Keep the frozen
file out of line-ending conversion (`<path> -text` in .gitattributes), or a checkout that rewrites
line endings fails the check on a text nobody edited.

Standard library only. git is needed for --history and for that part of --control.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path, PurePosixPath

MARKER = b"<!-- ---8<--- everything after this line is the received text, byte for byte ---8<--- -->\n"
RECORD = re.compile(r"<!--\s*frozen:(?P<body>.*?)-->")
FIELDS = ("path", "bytes", "sha256", "scope")
OPTIONAL = ("since",)
SCOPES = ("after-marker", "whole")
GIT_IDENTITY = ["-c", "user.name=freeze", "-c", "user.email=freeze@example.invalid",
                "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"]


class Refusal(Exception):
    """The input is not something this check can give an answer about; never a pass."""


# --- the check ------------------------------------------------------------------------------------

def repo_path(path: str, where: str) -> str:
    """A path relative to the repository, normalised, never leaving it."""
    p = PurePosixPath(path.replace("\\", "/"))
    if p.is_absolute() or ".." in p.parts or not p.parts:
        raise Refusal(f"{where}: {path!r} is not a path inside the repository")
    return p.as_posix()


def parse_records(text: str, where: str) -> list[dict[str, str]]:
    records = []
    for m in RECORD.finditer(text):
        fields: dict[str, str] = {}
        for token in m.group("body").split():
            key, sep, value = token.partition("=")
            if not sep or key not in FIELDS + OPTIONAL:
                raise Refusal(f"{where}: {token!r} is not one of {', '.join(FIELDS + OPTIONAL)} in {m.group(0)}")
            if key in fields:
                raise Refusal(f"{where}: {key} appears twice in {m.group(0)}; which value holds is ambiguous")
            fields[key] = value
        missing = [f for f in FIELDS if f not in fields]
        if missing:
            raise Refusal(f"{where}: a frozen record without {', '.join(missing)}: {m.group(0)}")
        if fields["scope"] not in SCOPES:
            raise Refusal(f"{where}: scope {fields['scope']!r} is neither of {', '.join(SCOPES)}")
        if not re.fullmatch(r"[0-9a-f]{64}", fields["sha256"]) or not fields["bytes"].isdigit():
            raise Refusal(f"{where}: {m.group(0)} does not carry a sha256 and a byte count; "
                          f"run --record on the file instead of writing the line by hand")
        if "since" in fields and not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", fields["since"]):
            raise Refusal(f"{where}: since={fields['since']!r} is not a full commit id; "
                          f"run --record with --since instead of writing it by hand")
        if int(fields["bytes"]) == 0:
            raise Refusal(f"{where}: {m.group(0)} freezes an empty text, and a check over nothing passes")
        fields["path"] = repo_path(fields["path"], where)
        records.append(fields)
    paths = [r["path"] for r in records]
    repeated = sorted({p for p in paths if paths.count(p) > 1})
    if repeated:
        raise Refusal(f"{where}: more than one record for {', '.join(repeated)}; which one holds is ambiguous")
    return records


def marker_lines(data: bytes) -> list[int]:
    """Where MARKER stands as a line of its own; a mention inside a line is not a marker."""
    found, i = [], data.find(MARKER)
    while i >= 0:
        if i == 0 or data[i - 1:i] == b"\n":
            found.append(i)
        i = data.find(MARKER, i + 1)
    return found


def frozen_bytes(data: bytes, scope: str, where: str) -> bytes:
    """What the digest is over. Bytes, never decoded text: the digest is a claim about bytes."""
    if scope == "whole":
        return data
    found = marker_lines(data)
    if not found:
        raise Refusal(f"{where}: no marker line, so the frozen text cannot be located (the marker must "
                      f"be a line of its own, byte for byte: converted line endings or a trailing space hide it)")
    if len(found) > 1:
        raise Refusal(f"{where}: more than one marker line; which text is frozen is ambiguous")
    return data[found[0] + len(MARKER):]


def verify(record: dict[str, str], data: bytes, where: str) -> str | None:
    """None when the bytes are the recorded ones, otherwise what differs."""
    try:
        body = frozen_bytes(data, record["scope"], where)
    except Refusal as e:
        return str(e)
    digest = hashlib.sha256(body).hexdigest()
    if len(body) == int(record["bytes"]) and digest == record["sha256"]:  # the size is for the reader
        return None
    return (f"{where}: THE FROZEN TEXT HAS CHANGED\n"
            f"  recorded {record['bytes']} bytes, sha256 {record['sha256']}\n"
            f"  found    {len(body)} bytes, sha256 {digest}")


def read_records(root: Path, brief: str) -> list[dict[str, str]]:
    path = root / brief
    if not path.is_file():
        raise Refusal(f"{brief}: not found; the records of what is frozen live there")
    records = parse_records(path.read_text(encoding="utf-8"), brief)
    if not records:
        raise Refusal(f"{brief} holds no frozen record. Nothing was checked, which is not the same "
                      f"as nothing having changed; add the line `--record` prints")
    return records


def check(root: Path, brief: str, quiet: bool = False) -> list[str]:
    errors = []
    for record in read_records(root, brief):
        target = root / record["path"]
        if not target.is_file():
            errors.append(f"{record['path']}: recorded as frozen and not there")
            continue
        error = verify(record, target.read_bytes(), record["path"])
        if error:
            errors.append(error)
        elif not quiet:
            print(f"{record['path']}: unedited - {record['bytes']} bytes, sha256 {record['sha256'][:16]}..., "
                  f"scope {record['scope']}")
    return errors


def record_line(root: Path, path: str, whole: bool, since: str | None = None) -> str:
    rel = repo_path(path, "--record")
    target = root / rel
    if not target.is_file():
        raise Refusal(f"{rel}: no such file to freeze")
    scope = "whole" if whole else "after-marker"
    body = frozen_bytes(target.read_bytes(), scope, rel)
    if not body:
        raise Refusal(f"{rel}: nothing to freeze - the frozen text is empty")
    anchor = ""
    if since is not None:
        resolved = git(root, "rev-parse", "--verify", "--quiet", f"{since}^{{commit}}")
        if resolved.returncode != 0:
            raise Refusal(f"--since {since}: not a commit in this repository")
        commit = resolved.stdout.strip()
        blob = git(root, "show", f"{commit}:{rel}", binary=True)
        if blob.returncode != 0:
            raise Refusal(f"--since {since}: {rel} is not in that commit")
        if frozen_bytes(blob.stdout, scope, f"{rel}@{commit[:7]}") != body:
            raise Refusal(f"--since {since}: the frozen bytes in that commit differ from the file as it is now; "
                          f"name the commit from which these exact bytes have stood")
        anchor = f" since={commit}"
    return (f"<!-- frozen: path={rel} bytes={len(body)} sha256={hashlib.sha256(body).hexdigest()} "
            f"scope={scope}{anchor} -->")


# --- the history ----------------------------------------------------------------------------------

def git(root: Path, *args: str, binary: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(root), *GIT_IDENTITY, *args],
                          capture_output=True, text=not binary)


def ancestor(root: Path, a: str, b: str) -> bool:
    return git(root, "merge-base", "--is-ancestor", a, b).returncode == 0


def touching(root: Path, *spec: str) -> list[str]:
    """Commits that touched the paths, parents before children, merged side branches included."""
    return git(root, "rev-list", "--full-history", "--topo-order", "--reverse", *spec).stdout.split()


def measurements(root: Path, window: str) -> list[tuple[str, str]]:
    """Every commit that added a file under `window`, with one such file; a README excepted."""
    out = git(root, "log", "--full-history", "--topo-order", "--reverse", "--diff-filter=A",
              "--format=@%H", "--name-only", "--", window).stdout
    found: dict[str, str] = {}
    commit = None
    for line in out.splitlines():
        if line.startswith("@"):
            commit = line[1:]
        elif line.strip() and commit and Path(line).name.lower() != "readme.md":
            found.setdefault(commit, line.strip())
    return list(found.items())


def history(root: Path, brief: str, window: str | None, quiet: bool = False) -> list[str]:
    if shutil.which("git") is None:
        raise Refusal("git is not installed; the history cannot be read")
    shallow = git(root, "rev-parse", "--is-shallow-repository")
    if shallow.returncode != 0:
        raise Refusal(f"{root} is not a git repository: {shallow.stderr.strip()}")
    if shallow.stdout.strip() == "true":
        raise Refusal("a shallow clone holds one end of the history and would pass anything; "
                      "fetch the whole history (fetch-depth: 0 on actions/checkout)")
    if window and not (root / window).is_dir():
        raise Refusal(f"{window}: no such directory; a window over nothing is never closed, so it would pass")
    commits = touching(root, "HEAD", "--", brief)
    if not commits:
        raise Refusal(f"{brief} has never been committed; there is no history to check")

    errors: list[str] = []
    versions: list[tuple[str, dict[str, dict[str, str]]]] = []
    for c in commits:
        shown = git(root, "show", f"{c}:{brief}")
        try:
            recs = parse_records(shown.stdout, f"{brief}@{c[:7]}") if shown.returncode == 0 else []
        except Refusal as e:
            errors.append(str(e))
            recs = []
        versions.append((c, {r["path"]: r for r in recs}))
    current = {r["path"]: r for r in read_records(root, brief)}

    for path in current:
        if not any(path in recs for _, recs in versions):
            errors.append(f"the record for {path} is not committed yet; commit it before the first measurement")
    first = {}
    for c, recs in versions:
        for path, r in recs.items():
            first.setdefault(path, (c, r))

    for path, (since, record) in first.items():
        anchor = record.get("since", since)
        if anchor != since and not ancestor(root, anchor, since):
            errors.append(f"the record for {path} says since={anchor[:7]}, which is not an ancestor of "
                          f"{since[:7]}, where it was first recorded (or not a commit here at all)")
            anchor = since
        for c, recs in versions:
            if c == since or not ancestor(root, since, c):
                continue
            if path not in recs:
                errors.append(f"the record for {path} (first at {since[:7]}) is gone at {c[:7]}")
            elif recs[path] != record:
                errors.append(f"the record for {path} was rewritten at {c[:7]} (first recorded at {since[:7]}): "
                              f"a freeze that can be re-recorded is not a freeze")
        if path not in current:
            errors.append(f"the record for {path} (first at {since[:7]}) is gone from the working tree")
        elif current[path] != record:
            errors.append(f"the record for {path} was rewritten in the working tree (first recorded at {since[:7]})")

        checked = 0
        for c in [anchor, *touching(root, f"{anchor}..HEAD", "--", path)]:
            if c != anchor and not ancestor(root, anchor, c):
                continue
            blob = git(root, "show", f"{c}:{path}", binary=True)
            if blob.returncode != 0:
                errors.append(f"{path} is recorded as frozen at {anchor[:7]} and absent at {c[:7]}")
                continue
            error = verify(record, blob.stdout, f"{path}@{c[:7]}")
            if error:
                errors.append(error)
            checked += 1
        if not quiet:
            frozen = f", frozen since {anchor[:7]}" if anchor != since else ""
            print(f"{path}: recorded at {since[:7]}{frozen}; the same bytes in all {checked} commit(s) "
                  f"that held it since")

        if window:
            found = measurements(root, window)
            late = 0
            for c, added in found:
                if c == anchor:
                    errors.append(f"{path} was frozen in the same commit {c[:7]} that added {added}: a freeze "
                                  f"made with the first number cannot show that it came first")
                elif not ancestor(root, anchor, c):
                    errors.append(f"{path} was frozen at {anchor[:7]}, but {c[:7]} added {added} without it: "
                                  f"the window closes at the first measurement")
                else:
                    continue
                late += 1
            if not quiet:
                print(f"{path}: frozen at {anchor[:7]}, before {len(found) - late} of the {len(found)} commit(s) "
                      f"adding measurements under {window}" if found
                      else f"{window}: no measurement committed yet, so the window is open")
    return errors


# --- the control ----------------------------------------------------------------------------------

WRAPPER = b"---\nid: source-brief\ntitle: The brief as received\ntype: research\nstatus: active\n---\n\nThe frozen text.\n\n"
TEXT = b"# Brief\n\n## Open questions\n\n- [ ] Which subject?\n\nRQ1 is green at 5 % and twice the ruler.\n"
BRIEF_PATH = "docs/research/source-brief.md"


def _tree(d: Path, *, scope: str = "after-marker", records: str | None = None, text: bytes = TEXT) -> None:
    (d / "docs" / "research").mkdir(parents=True, exist_ok=True)
    (d / BRIEF_PATH).write_bytes((WRAPPER + MARKER + text) if scope == "after-marker" else text)
    if records is None:
        records = record_line(d, BRIEF_PATH, scope == "whole")
    (d / "BRIEF.md").write_text(f"# BRIEF\n\n{records}\n\nPins and amendments.\n", encoding="utf-8")


def _commit(d: Path, message: str) -> None:
    git(d, "add", "-A")
    git(d, "commit", "-q", "-m", message)


def _mutate(data: bytes, how: str) -> bytes:
    """Positional mutations, so that none of them depends on a string the file may not contain."""
    found = marker_lines(data)
    start = found[0] + len(MARKER) if found else 0
    if how == "flip":                       # one character in the middle of the frozen text
        i = start + (len(data) - start) // 2
        return data[:i] + (b"x" if data[i:i + 1] != b"x" else b"y") + data[i + 1:]
    if how == "newline":                    # what a tidying editor or a formatter adds
        return data + b"\n"
    if how == "crlf":                       # what a line-ending conversion does
        return data.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
    if how == "wrapper":                    # this repository's own prose, above the marker
        return b"<!-- a note -->\n" + data
    if how == "quote":                      # the wrapper naming the marker mid-line: not a marker
        return b"The frozen text starts below the line " + MARKER + data
    if how == "tick":                       # answering an open question in place
        i = data.find(b"- [ ]", start)
        return data if i < 0 else data[:i] + b"- [x]" + data[i + 5:]
    if how == "second-marker":
        return data + MARKER
    if how == "no-marker":
        return data.replace(MARKER, b"")
    raise ValueError(how)


class _Invalid(Exception):
    """A control case that could not have tested what its name says, e.g. a mutation that changed nothing."""


def control(root: Path, brief: str, verbose: bool = False) -> int:
    results: list[tuple[str, bool, str]] = []   # (case, behaved, detail)

    def case(name: str, reason: str | None, run) -> None:
        """`reason` None: the case must pass. Otherwise some error must contain `reason`."""
        with tempfile.TemporaryDirectory() as tmp:
            try:
                errors = run(Path(tmp))
            except Refusal as e:
                errors = [str(e)]
            except _Invalid as e:
                results.append((name, False, str(e)))
                return
            except Exception as e:  # a crash is neither a pass nor the failure the case names
                results.append((name, False, f"crashed: {type(e).__name__}: {e}"))
                return
        if reason is None:
            results.append((name, not errors, errors[0].splitlines()[0] if errors else "accepted"))
        else:
            hit = [e for e in errors if reason in e]
            detail = (hit or errors or ["accepted"])[0].splitlines()[0]
            results.append((name, bool(hit), detail if hit or not errors else f"failed for another reason: {detail}"))

    def plain(d: Path, **tree) -> list[str]:
        _tree(d, **tree)
        return check(d, "BRIEF.md", quiet=True)

    def mutated(d: Path, how: str, scope: str = "after-marker") -> list[str]:
        _tree(d, scope=scope)
        target = d / BRIEF_PATH
        before = target.read_bytes()
        after = _mutate(before, how)
        if after == before:
            raise _Invalid(f"the '{how}' mutation changed nothing; the case would test the original file")
        target.write_bytes(after)
        return check(d, "BRIEF.md", quiet=True)

    def with_record(d: Path, edit) -> list[str]:
        _tree(d)
        line = record_line(d, BRIEF_PATH, False)
        return plain(d, records=edit(line))

    def missing_file(d: Path) -> list[str]:
        _tree(d)
        (d / BRIEF_PATH).unlink()
        return check(d, "BRIEF.md", quiet=True)

    def empty(d: Path) -> list[str]:
        (d / "docs" / "research").mkdir(parents=True)
        (d / BRIEF_PATH).write_bytes(b"")
        empty_sha = hashlib.sha256(b"").hexdigest()
        return plain(d, scope="whole", text=b"",
                     records=f"<!-- frozen: path={BRIEF_PATH} bytes=0 sha256={empty_sha} scope=whole -->")

    changed, no_marker = "HAS CHANGED", "no marker line"
    sha = "0" * 64
    case("a clean freeze passes", None, lambda d: plain(d))
    case("one character changed in the frozen text fails", changed, lambda d: mutated(d, "flip"))
    case("one trailing newline added fails", changed, lambda d: mutated(d, "newline"))
    case("line endings converted fail a whole-file freeze", changed, lambda d: mutated(d, "crlf", "whole"))
    case("line endings converted lose the marker", no_marker, lambda d: mutated(d, "crlf"))
    case("a checkbox ticked in a whole-file freeze fails", changed, lambda d: mutated(d, "tick", "whole"))
    case("the wrapper above the marker edited passes", None, lambda d: mutated(d, "wrapper"))
    case("the wrapper quoting the marker mid-line passes", None, lambda d: mutated(d, "quote"))
    case("a frozen text quoting the marker mid-line passes", None,
         lambda d: plain(d, text=TEXT + b"The study's own line reads " + MARKER))
    case("a second marker line fails", "more than one marker", lambda d: mutated(d, "second-marker"))
    case("the marker removed fails", no_marker, lambda d: mutated(d, "no-marker"))
    case("a BRIEF.md with no record fails", "holds no frozen record", lambda d: plain(d, records="No record here."))
    case("a record naming a missing file fails", "not there", missing_file)
    case("a hand-written record without a digest fails", "does not carry a sha256",
         lambda d: plain(d, records=f"<!-- frozen: path={BRIEF_PATH} bytes=<n> sha256=<hex> scope=whole -->"))
    case("two records for one path fail", "more than one record",
         lambda d: with_record(d, lambda line: line + "\n" + line.replace("after-marker", "whole")))
    case("a key given twice fails", "appears twice",
         lambda d: with_record(d, lambda line: line.replace(" -->", " path=docs/other.md -->")))
    case("an unknown key fails", "is not one of", lambda d: with_record(d, lambda line: line.replace(" -->", " note=x -->")))
    case("an unknown scope fails", "is neither of", lambda d: with_record(d, lambda line: line.replace("after-marker", "lines")))
    case("an empty frozen text fails", "freezes an empty text", empty)
    case("recording an empty text is refused", "nothing to freeze",
         lambda d: (plain(d), (d / BRIEF_PATH).write_bytes(WRAPPER + MARKER), record_line(d, BRIEF_PATH, False))[2])
    case("a path outside the repository fails", "not a path inside the repository",
         lambda d: plain(d, records=f"<!-- frozen: path=../outside.md bytes=1 sha256={sha} scope=whole -->"))
    case("recording a missing file is refused", "no such file", lambda d: record_line(d, "missing.md", False))
    case("a since that is not a full commit id fails", "not a full commit id",
         lambda d: with_record(d, lambda line: line.replace(" -->", " since=abc1234 -->")))

    if shutil.which("git") is None:
        results.append(("the history rules", False, "git is not installed, so they cannot be shown to fire"))
    else:
        def repo(d: Path) -> Path:
            d.mkdir(parents=True, exist_ok=True)
            git(d, "init", "-q")
            _tree(d)
            _commit(d, "freeze")
            return d

        def hist(d: Path, window: str | None = None) -> list[str]:
            return history(d, "BRIEF.md", window, quiet=True)

        def rewritten(d: Path) -> list[str]:
            repo(d)
            target = d / BRIEF_PATH
            target.write_bytes(_mutate(target.read_bytes(), "flip"))
            text = (d / "BRIEF.md").read_text(encoding="utf-8")
            (d / "BRIEF.md").write_text(RECORD.sub(record_line(d, BRIEF_PATH, False), text), encoding="utf-8")
            _commit(d, "edit the text and re-record it in one commit")
            if check(d, "BRIEF.md", quiet=True):
                raise _Invalid("the plain check failed on a re-recorded text, so this case proves nothing about history")
            return hist(d)

        def rewritten_uncommitted(d: Path) -> list[str]:
            repo(d)
            target = d / BRIEF_PATH
            target.write_bytes(_mutate(target.read_bytes(), "flip"))
            text = (d / "BRIEF.md").read_text(encoding="utf-8")
            (d / "BRIEF.md").write_text(RECORD.sub(record_line(d, BRIEF_PATH, False), text), encoding="utf-8")
            return hist(d)

        def restored(d: Path) -> list[str]:
            repo(d)
            target = d / BRIEF_PATH
            original = target.read_bytes()
            target.write_bytes(_mutate(original, "flip"))
            _commit(d, "move a threshold")
            target.write_bytes(original)
            _commit(d, "and move it back")
            return hist(d)

        def merged_branch(d: Path) -> list[str]:
            repo(d)
            main = git(d, "symbolic-ref", "--short", "HEAD").stdout.strip()
            git(d, "checkout", "-q", "-b", "side")
            target = d / BRIEF_PATH
            original = target.read_bytes()
            target.write_bytes(_mutate(original, "flip"))
            _commit(d, "move a threshold on a branch")
            (d / "logs").mkdir()
            (d / "logs" / "a.log").write_text("a number\n")
            _commit(d, "measure")
            target.write_bytes(original)
            _commit(d, "move it back")
            git(d, "checkout", "-q", main)
            merge = git(d, "merge", "-q", "--no-ff", "-m", "merge the study branch", "side")
            if merge.returncode != 0:
                raise _Invalid(f"could not build the merge: {merge.stderr.strip()}")
            return hist(d, "logs")

        def gone(d: Path) -> list[str]:
            repo(d)
            line = record_line(d, BRIEF_PATH, False)
            (d / "BRIEF.md").write_text("# BRIEF\n\nNo record any more.\n", encoding="utf-8")
            _commit(d, "drop the record")
            (d / "BRIEF.md").write_text(f"# BRIEF\n\n{line}\n", encoding="utf-8")
            _commit(d, "and put it back")
            return hist(d)

        def uncommitted(d: Path) -> list[str]:
            d.mkdir(parents=True, exist_ok=True)
            git(d, "init", "-q")
            (d / "BRIEF.md").write_text("# BRIEF\n", encoding="utf-8")
            _commit(d, "a brief with no record yet")
            _tree(d)
            return hist(d)

        def vanished(d: Path) -> list[str]:
            repo(d)
            target = d / BRIEF_PATH
            original = target.read_bytes()
            target.unlink()
            _commit(d, "lose the frozen file")
            target.write_bytes(original)
            _commit(d, "and restore it")
            return hist(d)

        def window(d: Path, order: str) -> list[str]:
            d.mkdir(parents=True, exist_ok=True)
            git(d, "init", "-q")
            (d / "logs").mkdir()
            (d / "logs" / "README.md").write_text("What the logs are.\n")
            _commit(d, "a README under logs/ is not a measurement")
            if order == "log-first":
                (d / "logs" / "first.log").write_text("a number\n")
                _commit(d, "first measurement")
            _tree(d)
            if order == "same-commit":
                (d / "logs" / "first.log").write_text("a number\n")
            _commit(d, "freeze")
            if order == "freeze-first":
                (d / "logs" / "first.log").write_text("a number\n")
                _commit(d, "first measurement")
            return hist(d, "logs")

        def gone_from_tree(d: Path) -> list[str]:
            repo(d)
            (d / "docs" / "prior.md").write_bytes(TEXT)
            first_line = record_line(d, BRIEF_PATH, False)
            both = first_line + "\n" + record_line(d, "docs/prior.md", True)
            (d / "BRIEF.md").write_text(f"# BRIEF\n\n{both}\n", encoding="utf-8")
            _commit(d, "freeze a second file")
            (d / "BRIEF.md").write_text(f"# BRIEF\n\n{first_line}\n", encoding="utf-8")
            return hist(d)

        def older_branch(d: Path) -> list[str]:
            d.mkdir(parents=True, exist_ok=True)
            git(d, "init", "-q")
            (d / "docs" / "research").mkdir(parents=True)
            (d / BRIEF_PATH).write_bytes(WRAPPER + MARKER + b"a draft\n")
            (d / "BRIEF.md").write_text("# BRIEF\n\nNot frozen yet.\n", encoding="utf-8")
            _commit(d, "a draft")
            main = git(d, "symbolic-ref", "--short", "HEAD").stdout.strip()
            git(d, "checkout", "-q", "-b", "draft")
            (d / BRIEF_PATH).write_bytes(WRAPPER + MARKER + b"another draft\n")
            (d / "BRIEF.md").write_text("# BRIEF\n\nStill not frozen.\n", encoding="utf-8")
            _commit(d, "redraft on a branch")
            git(d, "checkout", "-q", main)
            _tree(d)
            _commit(d, "freeze")
            merge = git(d, "merge", "-q", "--no-ff", "-X", "ours", "-m", "merge the old draft branch", "draft")
            if merge.returncode != 0 or check(d, "BRIEF.md", quiet=True):
                raise _Invalid(f"could not build the merge with the frozen text kept: {merge.stderr.strip()}")
            return hist(d)

        def shallow(d: Path) -> list[str]:
            origin = repo(d / "origin")
            (origin / "logs").mkdir()
            (origin / "logs" / "a.log").write_text("x\n")
            _commit(origin, "second")
            cloned = subprocess.run(["git", "clone", "-q", "--depth", "1", f"file://{origin}", str(d / "clone")],
                                    capture_output=True, text=True)
            if cloned.returncode != 0:
                raise _Invalid(f"could not make a shallow clone to test with: {cloned.stderr.strip()}")
            return hist(d / "clone")

        # A study that froze its text by other means and adopts the record later: `since` anchors it.
        def head(d: Path) -> str:
            return git(d, "rev-parse", "HEAD").stdout.strip()

        def adopted(d: Path, order: str) -> list[str]:
            d.mkdir(parents=True, exist_ok=True)
            git(d, "init", "-q")
            (d / "logs").mkdir()
            (d / "logs" / "README.md").write_text("What the logs are.\n")
            _commit(d, "a README under logs/ is not a measurement")
            if order == "log-first":
                (d / "logs" / "first.log").write_text("a number\n")
                _commit(d, "first measurement")
            target = d / BRIEF_PATH
            if order == "anchor-differs":
                _tree(d, records="Held by another mechanism.")
                target.write_bytes(_mutate(target.read_bytes(), "flip"))
                _commit(d, "a text that is not the one frozen later")
                anchor = head(d)
            _tree(d, records="Held by another mechanism.")
            _commit(d, "freeze the text, the digest kept elsewhere")
            if order != "anchor-differs":
                anchor = head(d)
            if order == "edited-between":
                original = target.read_bytes()
                target.write_bytes(_mutate(original, "flip"))
                _commit(d, "move a threshold")
                target.write_bytes(original)
                _commit(d, "and move it back")
            if order == "side-branch":
                main = git(d, "symbolic-ref", "--short", "HEAD").stdout.strip()
                git(d, "checkout", "-q", "-b", "side")
                (d / "notes.md").write_text("a side note\n")
                _commit(d, "a commit main never merges")
                anchor = head(d)
                git(d, "checkout", "-q", main)
            if order != "log-first":
                (d / "logs" / "first.log").write_text("a number\n")
                _commit(d, "first measurement")
            if order in ("anchor-differs", "side-branch"):    # --record would refuse or cannot see it
                line = record_line(d, BRIEF_PATH, False).replace(" -->", f" since={anchor} -->")
            else:
                line = record_line(d, BRIEF_PATH, False, since=anchor)
            (d / "BRIEF.md").write_text(f"# BRIEF\n\n{line}\n", encoding="utf-8")
            _commit(d, "adopt the record after the study began")
            return hist(d, "logs")

        def adopted_unanchored(d: Path) -> list[str]:
            d.mkdir(parents=True, exist_ok=True)
            git(d, "init", "-q")
            _tree(d, records="Held by another mechanism.")
            (d / "logs").mkdir()
            _commit(d, "freeze the text, the digest kept elsewhere")
            (d / "logs" / "first.log").write_text("a number\n")
            _commit(d, "first measurement")
            (d / "BRIEF.md").write_text(f"# BRIEF\n\n{record_line(d, BRIEF_PATH, False)}\n", encoding="utf-8")
            _commit(d, "adopt the record without saying since when")
            return hist(d, "logs")

        def record_since_differs(d: Path) -> list[str]:
            d.mkdir(parents=True, exist_ok=True)
            git(d, "init", "-q")
            _tree(d, records="Not frozen yet.")
            target = d / BRIEF_PATH
            target.write_bytes(_mutate(target.read_bytes(), "flip"))
            _commit(d, "a draft")
            first = head(d)
            _tree(d, records="Not frozen yet.")
            _commit(d, "the text as frozen")
            return [record_line(d, BRIEF_PATH, False, since=first)]

        def moved_anchor(d: Path) -> list[str]:
            d.mkdir(parents=True, exist_ok=True)
            git(d, "init", "-q")
            _tree(d, records="Held by another mechanism.")
            _commit(d, "freeze the text")
            entered = head(d)
            (d / "notes.md").write_text("later\n")
            _commit(d, "something else")
            later = head(d)
            (d / "BRIEF.md").write_text(f"# BRIEF\n\n{record_line(d, BRIEF_PATH, False, since=entered)}\n",
                                        encoding="utf-8")
            _commit(d, "adopt the record")
            (d / "BRIEF.md").write_text(f"# BRIEF\n\n{record_line(d, BRIEF_PATH, False, since=later)}\n",
                                        encoding="utf-8")
            _commit(d, "move the anchor")
            return hist(d)

        case("an untouched history passes", None, lambda d: hist(repo(d)))
        case("text and record rewritten in one commit fail the history", "was rewritten at", rewritten)
        case("text and record rewritten, not yet committed, fail the history", "rewritten in the working tree",
             rewritten_uncommitted)
        case("a frozen text edited and later restored fails the history", changed, restored)
        case("an edit made and undone on a merged branch fails the history", changed, merged_branch)
        case("a record dropped in a later commit fails the history", "is gone", gone)
        case("a record never committed fails the history", "not committed yet", uncommitted)
        case("a record dropped from the working tree fails the history", "gone from the working tree", gone_from_tree)
        case("a draft edited on a branch older than the freeze, merged after it, passes", None, older_branch)
        case("a frozen file missing from a later commit fails the history", "absent at", vanished)
        case("a freeze made after the first log fails --window", "the window closes", lambda d: window(d, "log-first"))
        case("a freeze in the same commit as the first log fails --window", "same commit",
             lambda d: window(d, "same-commit"))
        case("a freeze before the first log passes --window, a README before it", None,
             lambda d: window(d, "freeze-first"))
        case("a --window naming no directory is refused", "no such directory",
             lambda d: history(repo(d), "BRIEF.md", "log", quiet=True))
        case("a shallow clone is refused, not passed", "shallow clone", shallow)
        case("a record adopted after the first log, without since, fails --window", "the window closes",
             adopted_unanchored)
        case("a record adopted later, since the commit that froze the text before any log, passes --window", None,
             lambda d: adopted(d, "freeze-first"))
        case("a since after the first log fails --window", "the window closes", lambda d: adopted(d, "log-first"))
        case("a since whose bytes differ fails the history", changed, lambda d: adopted(d, "anchor-differs"))
        case("an edit made and undone between since and the record fails the history", changed,
             lambda d: adopted(d, "edited-between"))
        case("a since that is not an ancestor of the record fails the history", "not an ancestor",
             lambda d: adopted(d, "side-branch"))
        case("a since moved after it was recorded fails the history", "was rewritten at", moved_anchor)
        case("recording since a commit whose bytes differ is refused", "differ from the file", record_since_differs)

    # This repository's own frozen files, mutated in memory: the control of the real thing.
    own = root / brief
    own_records = parse_records(own.read_text(encoding="utf-8"), brief) if own.is_file() else []
    for record in own_records:
        target = root / record["path"]
        if not target.is_file():
            results.append((f"{record['path']}: present", False, "missing, see the plain check"))
            continue
        data = target.read_bytes()
        intact = verify(record, data, "") is None
        results.append((f"{record['path']}: as committed passes", intact, "" if intact else "see the plain check"))
        if not intact:
            continue
        hows = ["flip", "newline"] + (["tick"] if b"- [ ]" in frozen_bytes(data, record["scope"], "") else [])
        for how in hows:
            after = _mutate(data, how)
            results.append((f"{record['path']}: '{how}' fails", after != data and verify(record, after, "") is not None,
                            "" if after != data else "the mutation changed nothing"))
        if record["scope"] == "after-marker":
            after = _mutate(data, "wrapper")
            results.append((f"{record['path']}: wrapper edit passes", verify(record, after, "") is None, ""))
    if not own_records:
        print(f"note: {brief} not found or holds no record here; only the synthetic cases ran")

    print("control for brief_freeze.py")
    for name, behaved, detail in results:
        shown = detail if detail and (verbose or not behaved) else ""
        print(f"  {'ok  ' if behaved else 'FAIL'} {name}{': ' + shown if shown else ''}")
    bad = sum(1 for _, behaved, _ in results if not behaved)
    print(f"{len(results) - bad}/{len(results)} cases behave")
    return 1 if bad else 0


# --- entry ----------------------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", default=".", help="the study's repository (default: the current directory)")
    parser.add_argument("--brief", default="BRIEF.md", help="the file holding the frozen records")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--control", action="store_true", help="prove that each rule fires")
    mode.add_argument("--history", action="store_true", help="check the record and the bytes across git history")
    mode.add_argument("--record", metavar="PATH", help="print the record line for PATH")
    parser.add_argument("--window", metavar="DIR", help="with --history: the freeze must predate every measurement under DIR")
    parser.add_argument("--whole", action="store_true", help="with --record: hash the whole file, no marker")
    parser.add_argument("--since", metavar="REV", help="with --record: the commit from which these bytes have "
                                                       "stood, for a study adopting the record after it began")
    parser.add_argument("-v", "--verbose", action="store_true", help="with --control: say why each case came out as it did")
    args = parser.parse_args()
    root = Path(args.root)
    if args.window and not args.history:
        parser.error("--window goes with --history")
    if args.whole and not args.record:
        parser.error("--whole goes with --record")
    if args.since and not args.record:
        parser.error("--since goes with --record")
    try:
        if args.control:
            return control(root, args.brief, args.verbose)
        if args.record:
            print(record_line(root, args.record, args.whole, args.since))
            return 0
        errors = history(root, args.brief, args.window) if args.history else check(root, args.brief)
    except Refusal as e:
        print(f"REFUSED: {e}", file=sys.stderr)
        return 1
    for e in errors:
        print(e, file=sys.stderr)
    print(f"{'FAILED' if errors else 'ok'}: {len(errors)} problem(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
