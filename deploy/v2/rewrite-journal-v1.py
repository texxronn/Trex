#!/usr/bin/env python3
"""Rewrite a trex journal to the uniform envelope (V2-PROPOSAL.md §6; V2-INGEST-PROVENANCE-PLAN.md §4).

Day-0 / dev only. This is a mechanical FORMAT TRANSLATION, not a semantic edit: every line
means exactly what it meant, only its shape changes.

  old fact      {"n":1,"kind":"fact","v":2,...,"ingestedAt":"<iso>"}
  new fact      {"n":1,"kind":"trex.fact","v":1,"atMs":<ms>,"env":"Dev1    ","source":"ING_0001",
                 "target":"        ","at":"<iso ms>",...,"externalId":...}
  old decision  {"n":1858,"kind":"decision","action":"USER_ACK",...,"at":"<iso>"}
  new decision  {"n":1858,"kind":"trex.decision","v":1,"atMs":<ms>,"env":"Dev1    ",
                 "source":"HUB_0001","target":"        ","at":"<iso ms>","action":"USER_ACK",...}

Header fields come first, in order: n, kind, v, atMs, env, source, target. `kind` is namespaced
and mandatory (`trex.`). `env`, `source`, `target` are `[A-Za-z0-9_]{1,8}` right-padded with
spaces to exactly 8 — so `none`/empty target is 8 spaces.

Writes to <journal>.v1 and never touches the input. It does NOT swap the file: the running
codec must speak v1 first (today it requires v:2 / ingestedAt / at and rejects unknown kinds),
so swap in by hand when the format change has landed.

Usage:
  rewrite-journal-v1.py JOURNAL [--env Dev1] [--fact-source ING_0001]
                                [--decision-source HUB_0001] [--target ''] [--include-unknown]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

V = 1
NAMESPACE = "trex"
CODE_RE = re.compile(r"[A-Za-z0-9_]{1,8}")
NONE_TARGET = ""  # becomes 8 spaces once padded


def pad(code: str) -> str:
    """Right-pad a logical code with spaces to exactly 8 characters."""
    return code + " " * (8 - len(code))


def to_millis(iso: str) -> int:
    """Parse an ISO-8601 instant with any fractional precision into epoch millis (UTC)."""
    s = iso.strip().replace("Z", "+00:00")
    m = re.match(r"^(.*\.)(\d+)([+-]\d{2}:?\d{2})$", s)
    if m:
        s = m.group(1) + m.group(2)[:6] + m.group(3)  # fromisoformat wants <= 6 digits
    dt = datetime.fromisoformat(s)
    return int(dt.timestamp() * 1000)


def iso_millis(ms: int) -> str:
    dt = datetime.fromtimestamp(ms / 1000, tz=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{ms % 1000:03d}Z"


def namespaced(kind: str) -> str:
    return kind if "." in kind else f"{NAMESPACE}.{kind}"


def envelope(rec: dict, at_ms: int, env: str, source: str, target: str) -> dict:
    return {
        "n": rec["n"],
        "kind": namespaced(rec["kind"]),
        "v": V,
        "atMs": at_ms,
        "env": env,
        "source": source,
        "target": target,
    }


def rewrite_line(rec: dict, env: str, fact_source: str, decision_source: str, target: str,
                 include_unknown: bool) -> dict | None:
    kind = rec.get("kind")
    if kind == "fact":
        at_ms = to_millis(rec["ingestedAt"])
        out = envelope(rec, at_ms, env, fact_source, target)
        out["at"] = iso_millis(at_ms)
        for key, value in rec.items():
            if key in ("n", "kind", "v", "ingestedAt"):
                continue
            out[key] = value
        return out
    if kind == "decision":
        at_ms = to_millis(rec["at"])
        out = envelope(rec, at_ms, env, decision_source, target)
        out["at"] = iso_millis(at_ms)
        for key, value in rec.items():
            if key in ("n", "kind", "at"):
                continue
            out[key] = value
        return out
    if include_unknown:
        at_ms = to_millis(rec.get("at", rec.get("ingestedAt", "1970-01-01T00:00:00Z")))
        out = envelope(rec, at_ms, env, fact_source, target)
        for key, value in rec.items():
            if key in ("n", "kind", "v", "at", "ingestedAt"):
                continue
            out[key] = value
        return out
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("journal", type=Path)
    ap.add_argument("--env", default="Dev1")
    ap.add_argument("--fact-source", default="ING_0001")
    ap.add_argument("--decision-source", default="HUB_0001")
    ap.add_argument("--target", default=NONE_TARGET)
    ap.add_argument("--include-unknown", action="store_true")
    args = ap.parse_args()

    for name, value in (("env", args.env), ("fact-source", args.fact_source),
                        ("decision-source", args.decision_source)):
        if not CODE_RE.fullmatch(value):
            print(f"error: {name}={value!r} must be 1..8 chars of [A-Za-z0-9_]", file=sys.stderr)
            return 2
    if args.target != "" and not CODE_RE.fullmatch(args.target):
        print("error: target must be empty or 1..8 chars of [A-Za-z0-9_]", file=sys.stderr)
        return 2

    env = pad(args.env)
    fact_source = pad(args.fact_source)
    decision_source = pad(args.decision_source)
    target = pad(args.target)

    out_path = args.journal.with_suffix(args.journal.suffix + ".v1")
    facts = decisions = unknown = 0
    prev_n = 0
    gaps: list[str] = []
    with args.journal.open("r", encoding="utf-8") as src, out_path.open("w", encoding="utf-8") as dst:
        for lineno, raw in enumerate(src, 1):
            raw = raw.strip()
            if not raw:
                continue
            rec = json.loads(raw)
            n = rec.get("n")
            if not isinstance(n, int):
                print(f"error: line {lineno} has no integer n", file=sys.stderr)
                return 1
            if n != prev_n + 1:
                gaps.append(f"n {prev_n} -> {n}")
            prev_n = n
            new = rewrite_line(rec, env, fact_source, decision_source, target, args.include_unknown)
            if new is None:
                unknown += 1
                print(f"error: line {lineno}: unknown kind {rec.get('kind')!r} (use --include-unknown)",
                      file=sys.stderr)
                return 1
            if rec.get("kind") == "fact":
                facts += 1
            elif rec.get("kind") == "decision":
                decisions += 1
            dst.write(json.dumps(new, ensure_ascii=False, separators=(",", ":")) + "\n")

    print(f"wrote {out_path}")
    print(f"  lines: {facts + decisions + unknown}  facts: {facts}  decisions: {decisions}"
          + (f"  unknown: {unknown}" if unknown else ""))
    print(f"  n: 1..{prev_n}" + (f"  GAPS: {', '.join(gaps)}" if gaps else "  (contiguous)"))
    print(f"  env={env!r} source={fact_source!r} target={target!r}  (input untouched)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
