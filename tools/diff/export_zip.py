#!/usr/bin/env python3
"""Compare two export zips entry-by-entry, normalising the one deliberately non-reproducible
field the exporter writes: <PublicationTimestamp>, a wall clock. Exit 0 iff every entry matches."""
import sys, zipfile, re, hashlib
TS = re.compile(rb'<PublicationTimestamp>[^<]*</PublicationTimestamp>')
a = zipfile.ZipFile(sys.argv[1]); b = zipfile.ZipFile(sys.argv[2])
bad = 0
if a.namelist() != b.namelist():
    print("  entry lists differ"); sys.exit(1)
for n in a.namelist():
    xa = TS.sub(b'<T/>', a.read(n)); xb = TS.sub(b'<T/>', b.read(n))
    ok = xa == xb
    print(f"  {n:28s} {'IDENTICAL' if ok else '*** DIFFERS'}  {len(xa)} B  sha {hashlib.sha256(xa).hexdigest()[:16]}")
    bad += not ok
sys.exit(1 if bad else 0)
