#!/usr/bin/env python3
"""Extract the Chichewa section of "Nyimbo za Chitsitsimutso" into JSON.

Source: a two-column scanned-then-OCR'd PDF whose text layer is geometrically
messy (words displaced, a column occasionally emitted out of order, stray OCR
duplicates). We read each page twice -- once per column -- with
`pdftotext -layout`, which groups words onto visual lines (so a justified
title is not shredded) while keeping every line intact.

Because the text layer can emit the right column before the left, hymns are
collected purely by their anchor number and sorted at the end; column order
therefore cannot corrupt the result.

A hymn starts at a standalone number line (occasionally glued to its title, or
y-merged onto the tail of the preceding body line -- both are split out). The
header (Chichewa title, optional key, optional English title, optional "Tune:")
runs up to the first `N.` verse marker and is parsed leniently.

The Chichewa section is pages 1-89 (the Tumbuka section starts with a
"CHITUMBUKA" header and restarts numbering at 1 on page 90).

Usage:
    python3 tools/hymns_extract.py <input.pdf> <output.json>

Only the output JSON is meant to be committed; the source PDF is not bundled.
"""
import json
import re
import subprocess
import sys

PAGES = range(1, 90)
LEFT = ("-x", "0", "-W", "310")
RIGHT = ("-x", "310", "-W", "302")
CROP_Y = ("-y", "20", "-H", "752")

MAX_NO = 400

ANCHOR = re.compile(r"^(\d{1,3})$")
ANCHOR_TITLE = re.compile(r"^(\d{1,3})\s+(\S.*)$")
TRAIL_ANCHOR = re.compile(r"^(.+?\S)\s+(\d{1,3})$")
VERSE = re.compile(r"^\d{1,3}\.\s*\S")
KEY_ONLY = re.compile(r"^[A-G][#b]?$")
KEY_LINE = re.compile(r"^[-–—]\s*([A-G][#b]?)$")
KEY_TAIL = re.compile(r"\s*[-–—]\s*([A-G][#b]?)$")


def column_lines(pdf, page, crop):
    out = subprocess.run(
        ["pdftotext", "-f", str(page), "-l", str(page), *crop, *CROP_Y,
         "-layout", pdf, "-"],
        capture_output=True,
        text=True,
    ).stdout
    lines = []
    for raw in out.replace("\f", "\n").split("\n"):
        s = re.sub(r"\s+", " ", raw).strip()
        if s:
            lines.append(s)
    return lines


def titleish(s):
    core = re.sub(r"[^A-Za-z]", "", s)
    return bool(core) and core == core.upper()


def split_header(header):
    """Return (title, title_en, key, tune). header is a list of lines."""
    tune = None
    key = None
    texts = []
    for s in header:
        if s.lower().startswith("tune"):
            tune = s.split(":", 1)[-1].strip() if ":" in s else s
            continue
        texts.append(s)
    if not texts:
        return None, None, key, tune
    title_parts = []
    en = None
    # first pass: collect title and key
    for s in texts:
        if s in ("-", "–", "—"):
            continue
        if KEY_ONLY.match(s):
            key = key or s
            continue
        m = KEY_LINE.match(s)
        if m:
            key = key or m.group(1)
            continue
        m = KEY_TAIL.search(s)
        if m:
            key = key or m.group(1)
            s2 = s[:m.start()].strip()
            if not s2:
                continue
            s = s2
        if titleish(s):
            title_parts.append(s)
            continue
        title_parts.append(s)
    # now split: among title_parts, leading titleish are title, first short non-titleish is en if reasonable
    title_parts2 = []
    for s in title_parts:
        title_parts2.append(s)
    title = " ".join([p for p in title_parts2 if titleish(p)]).strip(" -–—")
    # find english: first non-titleish that is short and looks like a title (not a long body line)
    en_candidates = [p for p in title_parts2 if not titleish(p)]
    if en_candidates:
        cand = en_candidates[0]
        # if it's very long (many words), it's probably body not english title
        if len(cand) <= 60 and cand.count(" ") <= 8:
            en = cand
            # remove it conceptually from title? no, we already built title from titleish only
    return (title or None), en, key, tune


def parse_hymn(no, lines):
    header = []
    i = 0
    while i < len(lines) and not VERSE.match(lines[i]):
        header.append(lines[i])
        i += 1
    title, title_en, key, tune = split_header(header)
    stanzas = []
    cur = None
    for s in lines[i:]:
        if VERSE.match(s):
            if cur:
                stanzas.append(cur)
            cur = [s]
        elif cur is not None:
            cur.append(s)
        elif stanzas:
            stanzas[-1].append(s)
    if cur:
        stanzas.append(cur)
    if not stanzas and header:
        body_lines = []
        for s in header:
            if s in ("-", "–", "—"):
                continue
            if KEY_ONLY.match(s):
                continue
            m = re.match(r"^[-–—]\s*([A-G][#b]?)$", s)
            if m:
                continue
            m = re.search(r"\s*[-–—]\s*([A-G][#b]?)$", s)
            if m:
                s2 = s[:m.start()].strip()
                if not s2:
                    continue
                s = s2
            if title and s == title:
                continue
            if title_en and s == title_en:
                continue
            body_lines.append(s)
        if body_lines and not any(VERSE.match(x) for x in body_lines):
            stanzas = [body_lines]
    return {
        "no": no,
        "title": title or ("Nyimbo %d" % no),
        "titleEn": title_en,
        "key": key,
        "tune": tune,
        "stanzas": stanzas,
    }


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    pdf, out_path = sys.argv[1], sys.argv[2]

    all_lines = []
    for p in PAGES:
        all_lines += column_lines(pdf, p, LEFT)
        all_lines += column_lines(pdf, p, RIGHT)

    hymns = {}
    order = []
    no = None
    lines = []

    def flush():
        if no is not None:
            if no not in hymns:
                hymns[no] = parse_hymn(no, lines)
                order.append(no)

    for ln in all_lines:
        m = ANCHOR.match(ln)
        if m and 1 <= int(m.group(1)) <= MAX_NO:
            flush()
            no, lines = int(m.group(1)), []
            continue
        m = ANCHOR_TITLE.match(ln)
        if m and 1 <= int(m.group(1)) <= MAX_NO and titleish(m.group(2)):
            flush()
            no, lines = int(m.group(1)), [m.group(2)]
            continue
        if no is not None and len(ln) < 60:
            m = TRAIL_ANCHOR.match(ln)
            if m and no + 1 <= int(m.group(2)) <= no + 3:
                lines.append(m.group(1))
                flush()
                no, lines = int(m.group(2)), []
                continue
        if no is not None:
            lines.append(ln)
    flush()

    # Fill numbering gaps with an explicit placeholder. A number whose header
    # was dropped by the source OCR (hymn 76) would otherwise vanish silently;
    # a visible "text unavailable" entry is honest about the gap and keeps the
    # numbering continuous for the reader.
    top = max(hymns)
    placeholders = [n for n in range(1, top + 1) if n not in hymns]
    for n in placeholders:
        hymns[n] = {
            "no": n,
            "title": "Text unavailable in source",
            "titleEn": None,
            "key": None,
            "tune": None,
            "stanzas": [["[The text of this hymn is missing from the source document.]"]],
        }

    result = [hymns[n] for n in sorted(hymns)]
    nums = [h["no"] for h in result]
    with_en = sum(1 for h in result if h["titleEn"])
    with_key = sum(1 for h in result if h["key"])
    no_stanza = sum(1 for h in result if not h["stanzas"])
    print("hymns parsed: %d" % len(result))
    print("  with english title: %d" % with_en)
    print("  with key: %d" % with_key)
    print("  with no stanzas: %d" % no_stanza)
    print("  number range: %s..%s" % (min(nums), max(nums)))
    print("  placeholders: %s" % (placeholders if placeholders else "none"))

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    print("wrote %s" % out_path)


if __name__ == "__main__":
    main()
