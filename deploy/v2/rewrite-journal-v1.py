#!/usr/bin/env python3
"""Rewrite a trex journal to the uniform envelope (V2-PROPOSAL.md §6; V2-INGEST-PROVENANCE-PLAN.md §4).

Day-0 / dev only. This is a mechanical FORMAT TRANSLATION, not a semantic edit: every line
means exactly what it meant, only its shape changes.

  old fact      {"n":1,"kind":"fact","v":2,...,"ingestedAt":"<iso>"}
  new fact      {"n":1,"kind":"fact","v":1,"atMs":<ms>,"source":"ING_0001","target":"        ",
                 "at":"<iso ms>",...,"externalId":...}
  old decision  {"n":1858,"kind":"decision","action":"USER_ACK",...,"at":"<iso>"}
  new decision  {"n":1858,"kind":"decision","v":1,"atMs":<ms>,"source":"HUB_0001","target":"        ",
                 "at":"<iso ms>","action":"USER_ACK",...}

Writes to <journal>.v1 and never touches the input. It does NOT swap the file: the running
codec must speak v1 first (today it requires v:2 / ingestedAt / at and rejects unknown kinds),
so swap in by hand when the format change has landed.

Usage:
  rewrite-journal-v1.py JOURNAL [--fact-source ING_0001] [--decision-source HUB_0001]
                                [--target '        '] [--include-unknown]

If an unknown `kind` is met (e.g. an `ingest` event already present) the line is refused unless
--include-unknown is given, in which case it is passed through with the envelope added.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

NONE_TARGET = "        "  # exactly 8 spaces
V = 1


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


def envelope(rec: dict, at_ms: int, source: str, target: str) -> dict:
    return {
        "n": rec["n"],
        "kind": rec["kind"],
        "v": V,
        "atMs": at_ms,
        "source": source,
        "target": target,
    }


def rewrite_line(rec: dict, fact_source: str, decision_source: str, target: str,
                 include_unknown: bool) -> dict | None:
    kind = rec.get("kind")
    if kind == "fact":
        at_ms = to_millis(rec["ingestedAt"])
        out = envelope(rec, at_ms, fact_source, target)
        out["at"] = iso_millis(at_ms)
        for key, value in rec.items():
            if key in ("n", "kind", "v", "ingestedAt"):
                continue
            out[key] = value
        return out
    if kind == "decision":
        at_ms = to_millis(rec["at"])
        out = envelope(rec, at_ms, decision_source, target)
        out["at"] = iso_millis(at_ms)
        for key, value in rec.items():
            if key in ("n", "kind", "at"):
                continue
            out[key] = value
        return out
    if include_unknown:
        at_ms = to_millis(rec.get("at", rec.get("ingestedAt", "1970-01-01T00:00:00Z")))
        out = envelope(rec, at_ms, fact_source, target)
        for key, value in rec.items():
            if key in ("n", "kind", "at", "ingestedAt"):
                continue
            out[key] = value
        return out
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("journal", type=Path)
    ap.add_argument("--fact-source", default="ING_0001")
    ap.add_argument("--decision-source", default="HUB_0001")
    ap.add_argument("--target", default=NONE_TARGET)
    ap.add_argument("--include-unknown", action="store_true")
    args = ap.parse_args()

    for name, value in (("fact-source", args.fact_source),
                        ("decision-source", args.decision_source)):
        if not re.fullmatch(r"[A-Za-z0-9_]{8}", value):
            print(f"error: {name}={value!r} must be exactly 8 chars of [A-Za-z0-9_]", file=sys.stderr)
            return 2
    if args.target != NONE_TARGET and not re.fullmatch(r"[A-Za-z0-9_]{8}", args.target):
        print(f"error: target should be exactly 8 chars of [A-Za-z0-9_] or the 8-space none sentinel",
              file=sys.stderr)
        return 2

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
            new = rewrite_line(rec, args.fact_source, args.decision_source, args.target,
                               args.include_unknown)
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
    print("  input untouched; swap in by hand once the codec speaks v1")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
