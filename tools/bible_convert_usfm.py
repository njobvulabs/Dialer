#!/usr/bin/env python3
"""Convert eBible.org USFM bundles into the app's `bible_<translation>.json` format.

Usage:
    python3 tools/bible_convert_usfm.py <usfm_dir> <out_json> [translation]

Input : a directory of `*.usfm` files from eBible.org (e.g. nya_usfm.zip unpacked).
Output: JSON matching the schema of `app/src/main/assets/bible_kjv.json`:
        [{"name": "...", "abbrev": "...", "chapters": [[verse, ...], ...]}, ...]
        written with a UTF-8 BOM (the app reads it with utf-8-sig).

The book names/abbrevs and the expected chapter counts are taken from the existing
KJV asset so both translations line up; each USFM `\id` code is matched by canonical
book order (66 books). Structural USFM markers are stripped; poetry line breaks
(\q*) are preserved as newlines; `\f ... \f*` footnotes are folded into `{...}`
markers (the reader's footnote syntax). `\wj ... \wj*` (Words of Jesus) markers are
stripped, keeping the text.
"""

import io
import json
import re
import sys


KJV_ASSET = "app/src/main/assets/bible_kjv.json"

# USFM inline word-processor markers: strip the token but keep the wrapped text.
INLINE_STRIP = [
    (r"\\wj\*", ""),
    (r"\\wj\s*", ""),
    (r"\\qs\*", ""),
    (r"\\qs\s*", ""),
    (r"\\tl\*", ""),
    (r"\\tl\s*", ""),
    (r"\\r\*", ""),
    (r"\\r\s*", ""),
    (r"\\add\*", ""),
    (r"\\add\s*", ""),
    (r"\\sc\*", ""),
    (r"\\sc\s*", ""),
]

# Markers that act as poetry line breaks: append a newline before any text.
POETRY = {"\\q", "\\q1", "\\q2", "\\q3", "\\q4", "\\qm1", "\\qm2", "\\qr", "\\qc", "\\qa"}

# Markers that are headings/notes: never emit their text into a verse.
HEADING = {
    "\\id", "\\h", "\\toc1", "\\toc2", "\\toc3", "\\mt1", "\\mt2", "\\mt3",
    "\\s1", "\\s2", "\\s3", "\\s4", "\\ms1", "\\mr", "\\sr", "\\sp", "\\d",
    "\\cl", "\\ca", "\\ca*", "\\io", "\\io1", "\\io2", "\\ior", "\\ior*",
}

MARKER_RE = re.compile(r"\s*(\\\S*)")


def strip_inline(text):
    for pattern, repl in INLINE_STRIP:
        text = re.sub(pattern, repl, text)
    return text


def strip_outer_footnotes(text):
    """Fold \f ... \f* footnotes into {note} markers; swallow standalone \f lines."""
    text = re.sub(r"\\f\s*([^\\]*(?:\\\w+\*[^\\]*)*?)\\f\*", lambda m: "{%s}" % m.group(1).strip(), text)
    return text


def parse_book(lines):
    chapters = []
    verses = []
    current_num = None
    current_text = []

    def flush_verse():
        nonlocal current_num, current_text
        if current_num is not None and current_text:
            verses.append((current_num, " ".join(" ".join(current_text).split())))
        current_num = None
        current_text = []

    for raw in lines:
        line = raw.rstrip("\n")
        if not line.strip():
            continue

        cm = re.match(r"\\c\s+(\d+)\s*$", line)
        if cm:
            flush_verse()
            if verses:
                verses.sort(key=lambda v: v[0])
                chapters.append([v[1] for v in verses])
            verses = []
            continue

        vm = re.match(r"\\v\s+(\d+)[ \t]*(.*)$", line)
        if vm:
            flush_verse()
            current_num = int(vm.group(1))
            tail = vm.group(2).strip()
            if tail:
                current_text.append(tail)
            continue

        if current_num is None:
            continue

        stripped = line.strip()
        marker_m = MARKER_RE.match(stripped)
        if marker_m:
            marker = marker_m.group(1)
            body = stripped[marker_m.end():].strip()
            if marker in HEADING:
                continue
            if marker in POETRY:
                current_text.append("\n")
            else:
                current_text.append(" ")
            if body:
                current_text.append(body)
        else:
            current_text.append(stripped)

    flush_verse()
    if verses:
        verses.sort(key=lambda v: v[0])
        chapters.append([v[1] for v in verses])
    return chapters


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    usfm_dir = sys.argv[1]
    out_json = sys.argv[2]
    translation = sys.argv[3] if len(sys.argv) > 3 else "nya"

    # Reference: book codes, names, abbrevs and chapter counts from the KJV asset.
    with io.open(KJV_ASSET, "r", encoding="utf-8-sig") as f:
        kjv = json.load(f)
    ref_codes = [
        "GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT", "1SA", "2SA",
        "1KI", "2KI", "1CH", "2CH", "EZR", "NEH", "EST", "JOB", "PSA", "PRO",
        "ECC", "SNG", "ISA", "JER", "LAM", "EZK", "DAN", "HOS", "JOL", "AMO",
        "OBA", "JON", "MIC", "NAM", "HAB", "ZEP", "HAG", "ZEC", "MAL", "MAT",
        "MRK", "LUK", "JHN", "ACT", "ROM", "1CO", "2CO", "GAL", "EPH", "PHP",
        "COL", "1TH", "2TH", "1TI", "2TI", "TIT", "PHM", "HEB", "JAS", "1PE",
        "2PE", "1JN", "2JN", "3JN", "JUD", "REV",
    ]
    assert len(ref_codes) == len(kjv) == 66, "expected 66 KJV books"

    files = sorted(__import__("glob").glob(f"{usfm_dir}/*.usfm"))
    assert files, f"no .usfm files under {usfm_dir}"

    parsed = {}  # usfm code -> list of chapters
    for path in files:
        with io.open(path, "r", encoding="utf-8-sig") as f:
            text = f.read()
        idm = re.search(r"\\id\s+([A-Z0-9]+)", text)
        assert idm, f"no \\id in {path}"
        code = idm.group(1)
        text = strip_inline(text)
        text = strip_outer_footnotes(text)
        parsed[code] = parse_book(text.splitlines())

    missing = [c for c in ref_codes if c not in parsed]
    assert not missing, f"missing USFM books: {missing}"

    out = []
    warnings = 0
    for i, code in enumerate(ref_codes):
        ref = kjv[i]
        chapters = parsed[code]
        expected = len(ref["chapters"])
        if len(chapters) != expected:
            print(
                f"WARNING: {ref['name']} ({code}) has {len(chapters)} chapters, "
                f"expected {expected}",
                file=sys.stderr,
            )
            warnings += 1
        for ci, ch in enumerate(chapters):
            ev = len(ref["chapters"][ci]) if ci < expected else 0
            if ev and len(ch) != ev:
                print(
                    f"WARNING: {ref['name']} {ci + 1} has {len(ch)} verses, expected {ev}",
                    file=sys.stderr,
                )
                warnings += 1
        out.append({"name": ref["name"], "abbrev": ref["abbrev"], "chapters": chapters})

    with io.open(out_json, "w", encoding="utf-8-sig") as f:
        json.dump(out, f, ensure_ascii=False)

    total_verses = sum(len(ch) for b in out for ch in b["chapters"])
    print(
        f"Wrote {translation}: {len(out)} books, {total_verses} verses -> {out_json} "
        f"({warnings} warnings)"
    )


if __name__ == "__main__":
    main()