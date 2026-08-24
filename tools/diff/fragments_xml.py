"""Canonical XML comparison of two directories of fragments: parses each pair, normalizes
(namespace-stripped tags, sorted attributes, whitespace-stripped text, element order preserved
— NeTEx order is schema-fixed), and reports per-file equality plus the first differing path.
Pure stdlib.

Usage: python3 xmlcompare.py <dirA> <dirB> [--show N]
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# xs:duration lexical forms differ between serializers for the SAME value (xsData: 'PT0H46M0S',
# JAXB: 'PT46M'); normalize to a value tuple before comparing.
_DURATION = re.compile(
    r"^(-)?P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$"
)


def norm_text(t):
    m = _DURATION.match(t)
    if m and t.startswith(("P", "-P")) and any(g is not None for g in m.groups()[1:]):
        sign, y, mo, d, h, mi, s = m.groups()
        months = int(y or 0) * 12 + int(mo or 0)
        seconds = int(d or 0) * 86400 + int(h or 0) * 3600 + int(mi or 0) * 60 + float(s or 0)
        return f"dur({sign or '+'}{months}mo,{seconds}s)"
    return t


def local(tag):
    return tag.rsplit("}", 1)[-1]


def norm(el):
    attrs = sorted((local(k), v.strip()) for k, v in el.attrib.items())
    text = norm_text((el.text or "").strip())
    children = [norm(c) for c in el]
    return (local(el.tag), tuple(attrs), text if not children else "", tuple(children))


def first_diff(a, b, path=""):
    p = f"{path}/{a[0]}"
    if a[0] != b[0]:
        return f"{path}: tag {a[0]} != {b[0]}"
    if a[1] != b[1]:
        da, db = dict(a[1]), dict(b[1])
        keys = sorted(set(da) | set(db))
        deltas = [f"{k}: {da.get(k)!r} != {db.get(k)!r}" for k in keys if da.get(k) != db.get(k)]
        return f"{p}: attrs {'; '.join(deltas)}"
    if a[2] != b[2]:
        return f"{p}: text {a[2]!r} != {b[2]!r}"
    if len(a[3]) != len(b[3]):
        ca = [c[0] for c in a[3]]
        cb = [c[0] for c in b[3]]
        return f"{p}: child count {len(ca)} != {len(cb)} ({ca} vs {cb})"
    for ca, cb in zip(a[3], b[3]):
        d = first_diff(ca, cb, p)
        if d:
            return d
    return None


dir_a, dir_b = Path(sys.argv[1]), Path(sys.argv[2])
show = int(sys.argv[sys.argv.index("--show") + 1]) if "--show" in sys.argv else 10
files_a = {p.name for p in dir_a.glob("*.xml")}
files_b = {p.name for p in dir_b.glob("*.xml")}
if files_a != files_b:
    print(f"file set mismatch: only-A={sorted(files_a - files_b)} only-B={sorted(files_b - files_a)}")
same, diff = 0, []
for name in sorted(files_a & files_b):
    na = norm(ET.parse(dir_a / name).getroot())
    nb = norm(ET.parse(dir_b / name).getroot())
    if na == nb:
        same += 1
    else:
        diff.append((name, first_diff(na, nb)))
print(f"[xmlcompare] {same} identical, {len(diff)} different (of {len(files_a & files_b)} compared)")
for name, d in diff[:show]:
    print(f"  DIFF {name}: {d}")
sys.exit(0 if not diff else 1)
