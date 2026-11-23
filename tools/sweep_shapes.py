#!/usr/bin/env python3
"""
Phase 3 -- replace hard-coded RoundedCornerShape(n.dp) literals with shape tokens.

ONLY values that map EXACTLY onto an existing token are rewritten, so the sweep is a
zero-visual-change refactor:

    3.dp  -> CornerRadius.xs          12.dp -> MaterialTheme.shapes.medium
    4.dp  -> shapes.extraSmall        14.dp -> CornerRadius.md
    8.dp  -> shapes.small             16.dp -> shapes.large
    10.dp -> CornerRadius.sm          21.dp -> CornerRadius.pill
                                  24.dp -> CornerRadius.xl
                                  28.dp -> shapes.extraLarge

Values with no exact token (2, 6, 18, 20, 22.dp) are deliberately LEFT ALONE and
reported: snapping them to a neighbouring token is a visible change, and this phase
must be verifiable without a device. Re-run with --report-nearest to see the proposed
mapping for those without applying it.

Usage:  python3 tools/sweep_shapes.py --all
        python3 tools/sweep_shapes.py --all --check
        python3 tools/sweep_shapes.py --all --report-nearest
"""
import io
import re
import sys
import glob
import os
import collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/main/java")

EXACT = {
    3: ("CornerRadius", "xs"),
    4: ("MaterialTheme.shapes", "extraSmall"),
    8: ("MaterialTheme.shapes", "small"),
    10: ("CornerRadius", "sm"),
    12: ("MaterialTheme.shapes", "medium"),
    14: ("CornerRadius", "md"),
    16: ("MaterialTheme.shapes", "large"),
    21: ("CornerRadius", "pill"),
    24: ("CornerRadius", "xl"),
    28: ("MaterialTheme.shapes", "extraLarge"),
}
NEAREST = {2: 3, 6: 4, 18: 16, 20: 21, 22: 24}

LIT = re.compile(r"RoundedCornerShape\(\s*(\d+)\.dp\s*\)")

# Never rewrite the file that DEFINES the tokens: turning
# `val xs = RoundedCornerShape(3.dp)` into `val xs = CornerRadius.xs` is
# self-referential and silently breaks initialisation.
SKIP = {"Shape.kt"}


def sweep(path, apply=False, nearest=False):
    src = io.open(path, encoding="utf-8").read()
    hits = collections.Counter()
    changed = 0

    # Refuse fully-qualified references: rewriting
    # `androidx.compose.foundation.shape.RoundedCornerShape(16.dp)` would splice the
    # token into the package path and produce
    # `androidx.compose.foundation.shape.MaterialTheme.shapes.large`.
    bad = re.findall(r"androidx\.compose\.foundation\.shape\.RoundedCornerShape\(\s*\d+\.dp", src)
    if bad:
        return -1, {"FULLY-QUALIFIED(%d)" % len(bad): 1}

    def repl(m):
        nonlocal changed
        v = int(m.group(1))
        hits[v] += 1
        target = EXACT.get(v)
        if not target and nearest and v in NEAREST:
            target = EXACT[NEAREST[v]]
        if not target:
            return m.group(0)
        changed += 1
        return "%s.%s" % target

    out = LIT.sub(repl, src)
    if changed and apply:
        if "CornerRadius" in out and "import com.example.ui.theme.CornerRadius" not in out:
            m = re.search(r"^import com\.example\..*$", out, re.M)
            if m:
                out = (
                    out[:m.start()]
                    + "import com.example.ui.theme.CornerRadius\n"
                    + out[m.start():]
                )
            else:
                m = re.search(r"^package .*$", out, re.M)
                out = (
                    out[:m.end()]
                    + "\n\nimport com.example.ui.theme.CornerRadius"
                    + out[m.end():]
                )
        io.open(path, "w", encoding="utf-8").write(out)
    return changed, dict(sorted(hits.items()))


def main():
    args = sys.argv[1:]
    apply = "--all" in args and "--check" not in args and "--report-nearest" not in args
    nearest = "--report-nearest" in args
    targets = sorted(
        p for p in glob.glob(os.path.join(SRC, "**/*.kt"), recursive=True)
    )
    totals = collections.Counter()
    leftover = collections.Counter()
    for p in targets:
        if os.path.basename(p) in SKIP:
            continue
        changed, hits = sweep(p, apply=apply, nearest=nearest)
        for v, n in hits.items():
            totals[v] += n
            if v not in EXACT and not nearest:
                leftover[v] += n
        if changed < 0:
            print("  %-34s SKIPPED: fully-qualified RoundedCornerShape (%d site(s)) - fix by hand"
                  % (os.path.basename(p), -changed))
            continue
        if changed:
            verb = "wrote " if apply else ("would " if not nearest else "nearest->")
            print("  %-34s %s%d literal(s)" % (os.path.basename(p), verb, changed))

    print("\nvalue usage across app:")
    for v, n in sorted(totals.items(), key=lambda kv: -kv[1]):
        e = EXACT.get(v)
        print("  %3d.dp  x%-4d %s" % (v, n, "-> %s.%s" % e if e else "NO EXACT TOKEN (left alone)"))
    if leftover and not nearest:
        print("\nnot rewritten (no exact token): %s" % dict(leftover))
        print("proposed nearest, if you approve a visible change:")
        for v, n in sorted(leftover.items()):
            if v in NEAREST:
                print("    %d.dp x%d -> %s.%s" % (v, n, *EXACT[NEAREST[v]]))
            else:
                print("    %d.dp x%d -> (no sane nearest; RoundedCornerShape(0) is "
                      "really CircleShape - decide by hand)" % (v, n))
    print("\nmode: %s" % ("APPLY" if apply else ("NEAREST REPORT" if nearest else "CHECK")))


if __name__ == "__main__":
    main()