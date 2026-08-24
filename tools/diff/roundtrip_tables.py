#!/usr/bin/env python3
"""Report tables from an rtcmp summary/result file: `tables.py <file> [--top N]`."""
import gzip
import json
import sys
from collections import Counter, defaultdict

path = sys.argv[1]
top = int(sys.argv[sys.argv.index("--top") + 1]) if "--top" in sys.argv else 40
d = json.load((gzip.open if path.endswith(".gz") else open)(path, "rt"))
c = Counter(d["counters"])
rows = d["rows"]
MODES = ["A0", "A1", "Bj", "Bp"]

print("### per-mode headline")
print("| mode | objects seen | lossless | with findings | byte-strict equal | equal after attr-order | equal after sibling-order |")
print("|---|---:|---:|---:|---:|---:|---:|")
loss_obj = defaultdict(int)
for r in rows:
    if r["verdict"] in ("MISSING", "CHANGED"):
        loss_obj[r["mode"]] += r["objs"]
for m in MODES:
    if not c.get("seen." + m):
        continue
    print("| %s | %d | %d | %d | %d | %d | %d |" % (
        m, c["seen." + m], c["seen." + m] - c.get("differs." + m, 0), c.get("differs." + m, 0),
        c.get("rung0_strict." + m, 0), c.get("rung1_attrorder." + m, 0),
        c.get("rung2_siborder." + m, 0)))

print("\n### LOSS (source value absent or changed in output)")
print("| mode | verdict | path | attr | occurrences | objects | feeds | sample source -> output |")
print("|---|---|---|---|---:|---:|---:|---|")
L = sorted([r for r in rows if r["verdict"] in ("MISSING", "CHANGED")], key=lambda r: -r["n"])
for r in L[:top]:
    s = r["samples"][0] if r["samples"] else ["", "", "", ""]
    print("| %s | %s | `%s` | %s | %d | %d | %d | `%s` -> `%s` |" % (
        r["mode"], r["verdict"], r["path"], ("`@%s`" % r["name"]) if r["name"] else r["kind"],
        r["n"], r["objs"], r.get("nfeeds", 1),
        s[2][:60].replace("|", "\\|").replace("\n", "\\n"),
        s[3][:60].replace("|", "\\|").replace("\n", "\\n")))
print("total LOSS rows: %d  occurrences: %d" % (len(L), sum(r["n"] for r in L)))

print("\n### UNEXPECTED ADDITIONS (outside the denormalisation allow-list)")
print("| mode | path | attr | occurrences | objects | feeds | value |")
print("|---|---|---|---:|---:|---:|---|")
A = sorted([r for r in rows if r["verdict"] == "ADDED-UNEXPECTED"], key=lambda r: -r["n"])
for r in A[:top]:
    s = r["samples"][0] if r["samples"] else ["", "", "", ""]
    print("| %s | `%s` | %s | %d | %d | %d | `%s` |" % (
        r["mode"], r["path"], ("`@%s`" % r["name"]) if r["name"] else r["kind"], r["n"],
        r["objs"], r.get("nfeeds", 1), s[3][:50].replace("|", "\\|")))
print("total ADDED-UNEXPECTED rows: %d  occurrences: %d" % (len(A), sum(r["n"] for r in A)))

print("\n### denormalisation arms actually applied (ADDED-ARM), root vs depth")
arm = defaultdict(Counter)
for r in rows:
    if r["verdict"] != "ADDED-ARM":
        continue
    where = "depth" if "/" in r["path"] else "root"
    arm[r["mode"]][(r["name"], where)] += r["n"]
names = sorted({n for m in arm.values() for (n, w) in m})
print("| attribute | " + " | ".join("%s root | %s depth" % (m, m) for m in MODES if m in arm) + " |")
print("|---" * (1 + 2 * len([m for m in MODES if m in arm])) + "|")
for n in names:
    cells = []
    for m in MODES:
        if m not in arm:
            continue
        cells += [str(arm[m][(n, "root")]), str(arm[m][(n, "depth")])]
    print("| `@%s` | %s |" % (n, " | ".join(cells)))

print("\n### A1 stored-blob provenance: which classes lost their verbatim capture to resolve()")
rm = {k[13:]: v for k, v in c.items() if k.startswith("remarshalled.")}
vb = {k[9:]: v for k, v in c.items() if k.startswith("verbatim.")}
tot = sum(rm.values()) + sum(vb.values())
if tot:
    print("re-marshalled %d / %d rows = %.2f%%; %d classes 100%% re-marshalled, %d classes 0%%"
          % (sum(rm.values()), tot, 100.0 * sum(rm.values()) / tot,
             sum(1 for k in set(rm) | set(vb) if rm.get(k, 0) and not vb.get(k, 0)),
             sum(1 for k in set(rm) | set(vb) if vb.get(k, 0) and not rm.get(k, 0))))
    print("| class | re-marshalled | verbatim | share |")
    print("|---|---:|---:|---:|")
    for cl in sorted(set(rm) | set(vb), key=lambda x: -rm.get(x, 0))[:25]:
        r, v = rm.get(cl, 0), vb.get(cl, 0)
        print("| %s | %d | %d | %.1f%% |" % (cl, r, v, 100.0 * r / (r + v)))

print("\n### three-way split (Bj vs Bp), by slot")
slots = defaultdict(lambda: defaultdict(list))
for r in rows:
    if r["verdict"] not in ("MISSING", "CHANGED"):
        continue
    if r["mode"] in ("Bj", "Bp"):
        slots[(r["kind"], r["path"], r["name"])][r["mode"]].append(r)
jonly = [k for k, v in slots.items() if "Bj" in v and "Bp" not in v]
ponly = [k for k, v in slots.items() if "Bp" in v and "Bj" not in v]
both = [k for k, v in slots.items() if len(v) == 2]
def tot(k, m):
    return sum(r["n"] for r in slots[k].get(m, ()))


def cellof(k, m):
    rs = slots[k].get(m)
    if not rs:
        return "-"
    return " + ".join("%s %d" % (r["verdict"], r["n"]) for r in sorted(rs, key=lambda r: -r["n"]))


for label, ks in (("JAVA-ONLY (port bug)", jonly), ("SHARED", both),
                  ("PYTHON-ONLY (badger defect)", ponly)):
    n = sum(tot(k, "Bj") + tot(k, "Bp") for k in ks)
    print("\n**%s** — %d slots, %d occurrences" % (label, len(ks), n))
    print("| path | attr | Java (Bj) | Python (Bp) | sample source -> output |")
    print("|---|---|---|---|---|")
    for k in sorted(ks, key=lambda k: -max(tot(k, "Bj"), tot(k, "Bp")))[:top]:
        v = slots[k]
        cand = sorted(v.get("Bj") or v.get("Bp"), key=lambda r: -r["n"])[0]
        s = cand["samples"][0]
        print("| `%s` | %s | %s | %s | `%s` -> `%s` |" % (
            k[1], ("`@%s`" % k[2]) if k[2] else k[0], cellof(k, "Bj"), cellof(k, "Bp"),
            s[2][:45].replace("|", "\\|").replace("\n", "\\n"),
            s[3][:45].replace("|", "\\|").replace("\n", "\\n")))

print("\n### other counters")
for k in sorted(c):
    if k.startswith(("verbatim.", "remarshalled.", "seen.", "identical.", "differs.", "rung")):
        continue
    print("  %-34s %d" % (k, c[k]))
