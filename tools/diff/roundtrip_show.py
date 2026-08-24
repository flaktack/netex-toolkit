#!/usr/bin/env python3
"""Reader for rtcmp results: `show.py <res.json.gz|summary.json> [mode] [--all]`."""
import gzip
import json
import sys
from collections import Counter

path = sys.argv[1]
modef = sys.argv[2] if len(sys.argv) > 2 and not sys.argv[2].startswith("-") else None
opener = gzip.open if path.endswith(".gz") else open
d = json.load(opener(path, "rt"))
c = Counter(d["counters"])

MODES = ["A0", "A1", "Bj", "Bp"]
print("== objects: %d ==" % c.get("objects", 0))
print("%-4s %9s %9s %9s %9s %9s %9s" % ("mode", "seen", "identical", "differs", "strict", "attrord", "sibord"))
for m in MODES:
    if not c.get("seen." + m):
        continue
    print("%-4s %9d %9d %9d %9d %9d %9d" % (
        m, c["seen." + m], c.get("identical." + m, 0), c.get("differs." + m, 0),
        c.get("rung0_strict." + m, 0), c.get("rung1_attrorder." + m, 0),
        c.get("rung2_siborder." + m, 0)))

rm = {k[13:]: v for k, v in c.items() if k.startswith("remarshalled.")}
vb = {k[9:]: v for k, v in c.items() if k.startswith("verbatim.")}
tot = sum(rm.values()) + sum(vb.values())
if tot:
    print("\n== A1 stored-blob provenance (JAXB ns2/ns3 fingerprint) ==")
    print("re-marshalled %d / %d = %.2f%%" % (sum(rm.values()), tot, 100.0 * sum(rm.values()) / tot))
    for cl in sorted(set(rm) | set(vb), key=lambda x: -rm.get(x, 0))[:20]:
        r, v = rm.get(cl, 0), vb.get(cl, 0)
        print("  %-38s remarshal=%-9d verbatim=%-9d %5.1f%%" % (cl, r, v, 100.0 * r / (r + v)))

other = {k: v for k, v in c.items()
         if not k.startswith(("verbatim.", "remarshalled.", "seen.", "identical.", "differs.",
                              "rung0_", "rung1_", "rung2_"))}
print("\n== other counters ==")
for k in sorted(other):
    print("  %-34s %d" % (k, other[k]))

print("\n== loss / addition classes ==")
rows = [r for r in d["rows"] if modef is None or r["mode"] == modef]
show_arm = "--all" in sys.argv
rows = [r for r in rows if show_arm or r["verdict"] != "ADDED-ARM"]
rows.sort(key=lambda r: (r["mode"], r["verdict"], -r["n"]))
lim = int(sys.argv[sys.argv.index("--lim") + 1]) if "--lim" in sys.argv else 60
for r in rows[:lim]:
    s = r["samples"][0] if r["samples"] else ["", "", "", ""]
    print("%-3s %-16s %s %-58s %-22s n=%-9d obj=%-8d feeds=%s" % (
        r["mode"], r["verdict"], r["kind"], r["path"][:58], r["name"][:22], r["n"], r["objs"],
        r.get("nfeeds", len(r.get("feeds", {})))))
    if s[2] or s[3]:
        print("      src=%r  out=%r  (%s)" % (s[2][:110], s[3][:110], s[0][:70]))
print("... %d rows total" % len(rows))
