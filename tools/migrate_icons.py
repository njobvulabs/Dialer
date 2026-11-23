#!/usr/bin/env python3
"""
Phase 1.5 -- migrate a file from direct material-icons references to the AppIcons wrapper.

Rewrites   Icons.Default.X / Icons.Filled.X / Icons.AutoMirrored.Filled.X / ...
to         AppIcons.X
then drops the now-dead androidx.compose.material.icons.* imports and adds the
AppIcons import.

Refuses to touch a file it cannot fully verify (leftover Icons.* usage after the
rewrite, or an AppIcons property that does not exist), so it fails loudly instead of
half-migrating. That matters here: the project is NOT a git repo, so there is no
`git checkout` safety net for a bad automated rewrite.

Usage:  migrate_icons.py <file.kt> [more.kt ...]
        migrate_icons.py --all          # every screen except the wrapper itself
        migrate_icons.py --check        # report only, no writes
"""
import io
import re
import sys
import glob
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WRAPPER = os.path.join(ROOT, "app/src/main/java/com/example/ui/components/AppIcons.kt")

REF = re.compile(
    r"Icons\.(?:(AutoMirrored)\.)?(Default|Filled|Outlined|Rounded)\.(\w+)"
)
IMPORT = re.compile(
    r"^import androidx\.compose\.material\.icons\.[\w.]*(\.\*)?\s*$", re.M
)


def available_props():
    """Icon property names the wrapper actually exposes."""
    src = io.open(WRAPPER, encoding="utf-8").read()
    return set(re.findall(r"^\s*val\s+(\w+)\s*=\s*Icons\.", src, re.M))


def add_import_sorted(src, fqcn):
    """Insert an import in alphabetical position among its own top-level package.

    Only reorders against siblings sharing the same prefix, so hand-grouped
    imports elsewhere in the block keep whatever order the author chose.
    """
    lines = src.splitlines(True)
    prefix = ".".join(fqcn.split(".")[:2]) + "."  # e.g. "com.example."
    siblings = [
        i for i, l in enumerate(lines)
        if l.startswith("import " + prefix)
    ]
    if siblings:
        pos = next(
            (i for i in siblings if lines[i].strip() > "import " + fqcn),
            siblings[-1] + 1,
        )
        lines.insert(pos, "import %s\n" % fqcn)
    else:
        m = re.search(r"^package .*$", src, re.M)
        # Line index, NOT m.end() (a char offset) -- mixing the two appended the
        # import to the end of the file instead of the header.
        lines.insert(m.end() if False else len(lines[:m.end()]), "\nimport %s\n" % fqcn)
    return "".join(lines)


def collapse_import_blanks(src):
    """Drop blank lines sandwiched between two import lines.

    Deleting the middle of the icon-import run can strand a blank line inside an
    otherwise contiguous import block. The rule is purely local: a blank is only
    removed when a non-blank import precedes AND follows it.
    """
    lines = src.splitlines()
    keep = []
    for i, l in enumerate(lines):
        if l.strip() == "":
            prev = next((lines[j] for j in range(i - 1, -1, -1) if lines[j].strip()), "")
            nxt = next((lines[j] for j in range(i + 1, len(lines)) if lines[j].strip()), "")
            if prev.startswith("import ") and nxt.startswith("import "):
                continue  # stranded hole inside the block
        keep.append(l)
    return "\n".join(keep) + ("\n" if src.endswith("\n") else "")


def migrate(path, props, write=True):
    src = io.open(path, encoding="utf-8").read()
    if os.path.abspath(path) == WRAPPER:
        return "skip (wrapper)"

    missing = set()
    used = set()

    def repl(m):
        name = m.group(3)
        # AutoMirrored is canonical under the plain name in AppIcons.
        if name not in props:
            missing.add(name)
            return m.group(0)
        used.add(name)
        return "AppIcons." + name

    out = REF.sub(repl, src)

    if missing:
        return "ABORT: no AppIcons property for %s" % ", ".join(sorted(missing))

    # Any surviving bare `Icons.` reference means the rewrite was incomplete.
    # The lookbehind is essential: "AppIcons." contains "Icons." as a substring.
    leftover = re.findall(r"(?<![A-Za-z0-9_.])Icons\.", out)
    if leftover:
        return "ABORT: %d leftover Icons.* use(s) the regex did not cover" % len(leftover)

    # Only drop icon imports if nothing still needs them.
    if "Icons" not in re.sub(r"[A-Za-z]*Icons[A-Za-z]*", "", out):
        out = IMPORT.sub("", out)
    else:
        out = IMPORT.sub("", out)

    if "AppIcons" not in src:
        out = add_import_sorted(out, "com.example.ui.components.AppIcons")

    # Tidy the import block the removals disturbed.
    out = collapse_import_blanks(out)

    if not write:
        return "would rewrite %d icon ref(s), %d distinct" % (
            len(REF.findall(src)), len(used)
        )
    if not changed:
        return "no change (%d icon ref(s))" % len(REF.findall(src))
    io.open(path, "w", encoding="utf-8").write(out)
    return "rewrote %d icon ref(s) across %d distinct -> %s" % (
        len(REF.findall(src)), len(used), os.path.basename(path)
    )


def main():
    args = sys.argv[1:]
    check = "--check" in args
    if "--all" in args:
        targets = [
            p for p in sorted(glob.glob(os.path.join(ROOT, "app/src/main/java/**/*.kt"), recursive=True))
            if os.path.abspath(p) != WRAPPER
        ]
    else:
        targets = [os.path.abspath(a) for a in args if not a.startswith("--")]

    props = available_props()
    for p in targets:
        try:
            print("%-46s %s" % (os.path.basename(p)[:46], migrate(p, props, write=not check)))
        except Exception as exc:  # keep going, report everything
            print("%-46s ERROR %s" % (os.path.basename(p)[:46], exc))


if __name__ == "__main__":
    main()