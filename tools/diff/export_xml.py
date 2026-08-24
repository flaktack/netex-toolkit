#!/usr/bin/env python3
"""
Streaming, normalising XML A/B comparator for the EPIP NeTEx export.

Compares two export zips entry-by-entry and element-by-element, applying the
normalisations the deviation ledger declares (EPIP_TRANSFORM #4.6 gml
parsed-value, #5/F7 attribute order + per-fragment ns decls +
PublicationTimestamp precision, plus the build-date version stamp) and counting
each one separately, so that everything not absorbed by a declared normalisation
is a finding.

Nothing is materialised in memory: both zips are read as streams, per-object
records go to disk, and the join is a streaming merge over LC_ALL=C-sorted
files.

Subcommands
  scan     one full streaming pass over one zip -> per-object hash records,
           shell skeletons, and an element/attribute census
  join     streaming merge-join of the two sides' sorted hash records
  census   diff the two sides' censuses
  shell    compare the shell skeletons (container structure + member order)
  extract  second pass: pull the canonical form of named objects out of a zip

Object model
  An "object" is the export's unit of storage: an element with an `id` whose
  only id-bearing ancestors are Frames -- concretely, an upper-case-initial
  child of a lower-case-initial container element, excluding *Frame.  Everything
  outside objects (PublicationDelivery, CompositeFrame, the frames, their
  FrameDefaults/ValidBetween, the container elements themselves) is the SHELL
  and is compared separately.

Hash ladder (per object).  Each stage adds one declared normalisation, so a
streaming join can attribute every difference to the first stage that absorbs it:
  hA  strict          attributes in document order, gml text verbatim,
                      numeric leaf text verbatim
  hB  +attr-order     attributes sorted by qualified name        (F7)
  hC  +gml-value      gml:pos / gml:posList / gml:coordinates text compared as
                      parsed floats                              (#4.6)
  hD  +num-value      Longitude/Latitude/Altitude/Distance/... leaf text
                      compared as parsed floats  (DIAGNOSTIC -- not a declared
                      normalisation; reported separately)
Always-on, because they are structural and unconditionally declared:
  - namespace declarations are dropped by the parser (names are compared as
    {uri}local) -- placement insensitivity is therefore total; the number of
    declarations per side is counted instead                     (F7)
  - all-whitespace text between child elements is dropped        (counted as W1)
  - the build-date stamp (CompositeFrame version, yyyyMMdd) is replaced by
    @STAMP@ wherever it appears as an attribute value            (counted)
  - PublicationTimestamp text is replaced by @TS@                (F7, counted)
Leaf text is NEVER stripped, and an empty element (<X/>) is NOT equal to an
absent one -- that is how the DaysOfWeek class stays visible.
"""

import argparse
import hashlib
import io
import json
import os
import re
import sys
import zipfile
from collections import Counter, defaultdict
from xml.etree import ElementTree as ET

NS_NETEX = "http://www.netex.org.uk/netex"
NS_GML = "http://www.opengis.net/gml/3.2"
NS_SIRI = "http://www.siri.org.uk/siri"
NS_XSI = "http://www.w3.org/2001/XMLSchema-instance"
PREFIX = {NS_NETEX: "", NS_GML: "gml:", NS_SIRI: "siri:", NS_XSI: "xsi:"}

GML_COORD = {"{%s}pos" % NS_GML, "{%s}posList" % NS_GML,
             "{%s}coordinates" % NS_GML}
# NeTEx leaf elements whose content is a number and whose text form can diverge
# without a value divergence.  DIAGNOSTIC stage only.
NUM_LEAF = {"Longitude", "Latitude", "Altitude", "Distance", "Precision",
            "Accuracy", "TotalDistance", "PercentageDistance"}
# Low-cardinality attributes worth a value census (schema defaults, order, srs).
VALUE_ATTRS = {"modification", "status", "publication", "order", "srsName",
               "versionRef", "nameOfRefClass", "all", "derivedFromVersionRef"}

STAMP_RE = re.compile(r"^20\d{6}$")


def qname(tag):
    """{uri}local -> prefixed local name, with the NeTEx default ns elided."""
    if tag and tag[0] == "{":
        uri, _, local = tag[1:].partition("}")
        p = PREFIX.get(uri)
        if p is None:
            return "{%s}%s" % (uri, local)
        return p + local
    return tag


def is_container(local):
    return local and local[0].islower()


# --------------------------------------------------------------------------
# canonicalisation
# --------------------------------------------------------------------------

class Canon:
    """Serialises one object subtree into four token streams (the hash ladder)
    plus census contributions.  One traversal, four hashers."""

    __slots__ = ("stamp", "ws_dropped", "mixed", "ns_seen", "census_e",
                 "census_a", "census_z", "census_v", "objclass", "hA", "hB",
                 "hC", "hD", "textbuf")

    def __init__(self, stamp, census_e, census_a, census_z, census_v):
        self.stamp = stamp
        self.census_e = census_e
        self.census_a = census_a
        self.census_z = census_z
        self.census_v = census_v
        self.ws_dropped = 0
        self.mixed = 0

    def run(self, elem, objclass, want_text=False):
        self.objclass = objclass
        self.ws_dropped = 0
        self.mixed = 0
        a = hashlib.blake2b(digest_size=8)
        b = hashlib.blake2b(digest_size=8)
        c = hashlib.blake2b(digest_size=8)
        d = hashlib.blake2b(digest_size=8)
        self.hA, self.hB, self.hC, self.hD = a, b, c, d
        self.textbuf = [] if want_text else None
        self._walk(elem, "")
        return (a.hexdigest(), b.hexdigest(), c.hexdigest(), d.hexdigest())

    def _emit(self, sa, sb=None, sc=None, sd=None):
        ba = sa.encode("utf-8")
        self.hA.update(ba)
        self.hB.update(ba if sb is None else sb.encode("utf-8"))
        self.hC.update(ba if sc is None else sc.encode("utf-8"))
        self.hD.update(ba if sd is None else sd.encode("utf-8"))
        if self.textbuf is not None:
            self.textbuf.append(sd if sd is not None
                                else sc if sc is not None
                                else sb if sb is not None else sa)

    def _walk(self, e, path):
        tag = qname(e.tag)
        here = tag if not path else path + "/" + tag
        ce = self.census_e
        k = (self.objclass, here)
        ce[k] = ce.get(k, 0) + 1

        items = list(e.items())
        # attribute values: normalise the build-date stamp
        norm = []
        for an, av in items:
            an = qname(an) if an and an[0] == "{" else an
            if av == self.stamp:
                av = "@STAMP@"
            norm.append((an, av))
            ca = self.census_a
            ka = (self.objclass, here, an)
            ca[ka] = ca.get(ka, 0) + 1
            if an in VALUE_ATTRS:
                cv = self.census_v
                kv = (self.objclass, here, an, av)
                cv[kv] = cv.get(kv, 0) + 1
        doc = "".join("\x02%s\x03%s" % (n, v) for n, v in norm)
        srt = "".join("\x02%s\x03%s" % (n, v) for n, v in sorted(norm))
        self._emit("\x01" + tag + doc, "\x01" + tag + srt,
                   "\x01" + tag + srt, "\x01" + tag + srt)

        kids = list(e)
        text = e.text
        if kids:
            # inter-element content: drop it when it is pure whitespace
            if text and text.strip():
                self.mixed += 1
                self._emit("\x04" + text)
            elif text:
                self.ws_dropped += 1
            for kid in kids:
                self._walk(kid, here)
                t = kid.tail
                if t and t.strip():
                    self.mixed += 1
                    self._emit("\x04" + t)
                elif t:
                    self.ws_dropped += 1
        else:
            if not text:
                kz = (self.objclass, here)
                self.census_z[kz] = self.census_z.get(kz, 0) + 1
            t = text or ""
            if e.tag in GML_COORD:
                self._emit("\x05" + t, None, "\x05" + numfmt(t), "\x05" + numfmt(t))
            elif tag in NUM_LEAF:
                self._emit("\x05" + t, None, None, "\x05" + numfmt(t))
            else:
                self._emit("\x05" + t)
        self._emit("\x06")


def numfmt(t):
    """Canonical parsed-value form of a whitespace-separated coordinate list."""
    out = []
    for tok in t.split():
        try:
            out.append(repr(float(tok)))
        except ValueError:
            out.append(tok)
    return " ".join(out)


# --------------------------------------------------------------------------
# scan: one streaming pass over one zip
# --------------------------------------------------------------------------

def scan_entry(args):
    zippath, entry, side, outdir, stamp, want_shell = args
    keyp = os.path.join(outdir, "keys", entry + ".tsv")
    shlp = os.path.join(outdir, "shell", entry + ".txt")
    census_e, census_a, census_z, census_v = {}, {}, {}, {}
    shell_e, shell_a = {}, {}
    ns_decls = Counter()
    ws_total = 0
    mixed_total = 0
    nobj = 0
    noid = Counter()
    dupguard = None

    canon = Canon(stamp, census_e, census_a, census_z, census_v)

    z = zipfile.ZipFile(zippath)
    fh = z.open(entry)
    kf = open(keyp, "w", buffering=1 << 20)
    sf = open(shlp, "w", buffering=1 << 20) if want_shell else None

    stack = []          # [elem, local, is_obj_root, n_children_seen]
    depth = 0
    in_obj = 0
    seq = 0
    ctx = ET.iterparse(fh, events=("start", "end", "start-ns"))
    for ev, node in ctx:
        if ev == "start-ns":
            ns_decls[node[1]] += 1
            continue
        if ev == "start":
            if stack:
                stack[-1][3] += 1
            if in_obj:
                stack.append([node, None, False, 0])
                depth += 1
                continue
            local = node.tag.rpartition("}")[2]
            parent_local = stack[-1][1] if stack else None
            objroot = (parent_local is not None
                       and is_container(parent_local)
                       and local[:1].isupper()
                       and not local.endswith("Frame"))
            stack.append([node, local, objroot, 0])
            depth += 1
            if objroot:
                in_obj = depth
            elif sf is not None:
                at = " ".join("%s=%s" % (qname(k), v if v != stamp else "@STAMP@")
                              for k, v in sorted(node.items()))
                sf.write("%dS %s %s\n" % (depth, qname(node.tag), at))
                ke = (qname(node.tag),)
                shell_e[ke] = shell_e.get(ke, 0) + 1
                for k2, v2 in node.items():
                    ka = (qname(node.tag), qname(k2) if k2[:1] == "{" else k2)
                    shell_a[ka] = shell_a.get(ka, 0) + 1
            continue

        # end
        elem, local, objroot, nkids = stack.pop()
        depth -= 1
        if in_obj and depth >= in_obj:
            continue
        if objroot:
            in_obj = 0
            oid = elem.get("id")
            ver = elem.get("version") or ""
            if ver == stamp:
                ver = "@STAMP@"
            hs = canon.run(elem, local)
            ws_total += canon.ws_dropped
            mixed_total += canon.mixed
            if oid is None:
                noid[local] += 1
                key = "%s|@NOID@%s|" % (local, hs[3])
            else:
                key = "%s|%s|%s" % (local, oid, ver)
            kf.write("%s\t%s\t%s\t%s\t%s\t%s\t%d\t%d\n"
                     % (key, hs[0], hs[1], hs[2], hs[3], entry,
                        canon.ws_dropped, canon.mixed))
            if sf is not None:
                sf.write("%dO %s\n" % (depth + 1, key))
            nobj += 1
            seq += 1
            # free: everything before this point in the container is done
            if stack:
                del stack[-1][0][:]
            else:
                elem.clear()
        else:
            if sf is not None:
                # shell leaf text (PublicationTimestamp etc.).  nkids, not
                # len(elem): completed objects are unlinked from their container
                # as we go, so len() lies about containers.
                if nkids == 0:
                    t = elem.text or ""
                    if local == "PublicationTimestamp":
                        t = "@TS@"
                    elif t == stamp:
                        t = "@STAMP@"
                    sf.write("%dT %s %s\n" % (depth + 1, qname(elem.tag), t))
                sf.write("%dE %s\n" % (depth + 1, qname(elem.tag)))
            elem.clear()
    kf.close()
    if sf is not None:
        sf.close()
    fh.close()
    z.close()
    return {
        "entry": entry, "objects": nobj, "ws": ws_total, "mixed": mixed_total,
        "ns_decls": dict(ns_decls), "noid": dict(noid),
        "census_e": {"\x1f".join(k): v for k, v in census_e.items()},
        "census_a": {"\x1f".join(k): v for k, v in census_a.items()},
        "census_z": {"\x1f".join(k): v for k, v in census_z.items()},
        "census_v": {"\x1f".join(k): v for k, v in census_v.items()},
        "shell_e": {"\x1f".join(k): v for k, v in shell_e.items()},
        "shell_a": {"\x1f".join(k): v for k, v in shell_a.items()},
    }


def detect_stamp(zippath):
    z = zipfile.ZipFile(zippath)
    name = "shared-01-resource.xml"
    if name not in z.namelist():
        name = z.namelist()[0]
    head = z.open(name).read(4000).decode("utf-8", "replace")
    z.close()
    m = re.search(r'<CompositeFrame id="[^"]*" version="(\d{8})"', head)
    return m.group(1) if m else "@none@"


def cmd_scan(a):
    import multiprocessing as mp
    outdir = os.path.join(a.out, a.side)
    os.makedirs(os.path.join(outdir, "keys"), exist_ok=True)
    os.makedirs(os.path.join(outdir, "shell"), exist_ok=True)
    z = zipfile.ZipFile(a.zip)
    entries = sorted(i.filename for i in z.infolist())
    sizes = {i.filename: i.file_size for i in z.infolist()}
    z.close()
    if a.only:
        pats = a.only.split(",")
        entries = [e for e in entries if any(e.startswith(p) for p in pats)]
    stamp = detect_stamp(a.zip)
    print("[scan] %s side=%s entries=%d stamp=%s workers=%d"
          % (a.zip, a.side, len(entries), stamp, a.workers), flush=True)
    # biggest first, so the long pole starts immediately
    entries.sort(key=lambda e: -sizes[e])
    jobs = [(a.zip, e, a.side, outdir, stamp, True) for e in entries]

    agg = {k: Counter() for k in ("census_e", "census_a", "census_z", "census_v",
                                  "shell_e", "shell_a", "ns_decls", "noid")}
    tot = Counter()
    per_entry = {}
    done = 0
    with mp.Pool(a.workers) as pool:
        for r in pool.imap_unordered(scan_entry, jobs):
            for k in agg:
                agg[k].update(r[k])
            tot["objects"] += r["objects"]
            tot["ws"] += r["ws"]
            tot["mixed"] += r["mixed"]
            per_entry[r["entry"]] = r["objects"]
            done += 1
            print("[scan] %3d/%d %-24s obj=%-9d" % (done, len(jobs), r["entry"],
                                                    r["objects"]), flush=True)
    meta = {"zip": a.zip, "side": a.side, "stamp": stamp,
            "entries": {e: sizes[e] for e in entries},
            "objects_per_entry": per_entry,
            "totals": dict(tot),
            "ns_decls": dict(agg["ns_decls"]), "noid": dict(agg["noid"])}
    with open(os.path.join(outdir, "meta.json"), "w") as f:
        json.dump(meta, f, indent=1)
    for k in ("census_e", "census_a", "census_z", "census_v", "shell_e", "shell_a"):
        with open(os.path.join(outdir, k + ".tsv"), "w") as f:
            for kk, v in sorted(agg[k].items()):
                f.write("%s\t%d\n" % (kk.replace("\x1f", "\t"), v))
    print("[scan] done objects=%d ws=%d mixed=%d"
          % (tot["objects"], tot["ws"], tot["mixed"]), flush=True)


# --------------------------------------------------------------------------
# join: streaming merge over sorted key files
# --------------------------------------------------------------------------

def read_group(fh, pending):
    """Yield (key, [rows]) groups from an LC_ALL=C-sorted tsv."""
    cur = pending[0]
    rows = pending[1]
    for line in fh:
        p = line.rstrip("\n").split("\t")
        if cur is None:
            cur, rows = p[0], [p]
        elif p[0] == cur:
            rows.append(p)
        else:
            yield cur, rows
            cur, rows = p[0], [p]
    if cur is not None:
        yield cur, rows


def cmd_join(a):
    ja = open(a.java)
    py = open(a.py)
    ga = read_group(ja, [None, None])
    gp = read_group(py, [None, None])
    stats = Counter()
    cls_stage = defaultdict(Counter)
    only_j = open(os.path.join(a.out, "only-java.txt"), "w")
    only_p = open(os.path.join(a.out, "only-py.txt"), "w")
    diffs = open(os.path.join(a.out, "diff-keys.txt"), "w")
    absorbed = open(os.path.join(a.out, "absorbed.txt"), "w")

    def stage_of(jr, pr):
        # first ladder rung at which the two agree
        if jr[1] == pr[1]:
            return "equal"
        if jr[2] == pr[2]:
            return "attr-order"
        if jr[3] == pr[3]:
            return "gml-value"
        if jr[4] == pr[4]:
            return "num-value"
        return "DIFF"

    ka = next(ga, None)
    kp = next(gp, None)
    while ka is not None or kp is not None:
        if kp is None or (ka is not None and ka[0] < kp[0]):
            stats["only_java"] += len(ka[1])
            cls_stage[ka[0].split("|")[0]]["only_java"] += len(ka[1])
            for r in ka[1]:
                only_j.write(r[0] + "\t" + r[5] + "\n")
            ka = next(ga, None)
        elif ka is None or kp[0] < ka[0]:
            stats["only_py"] += len(kp[1])
            cls_stage[kp[0].split("|")[0]]["only_py"] += len(kp[1])
            for r in kp[1]:
                only_p.write(r[0] + "\t" + r[5] + "\n")
            kp = next(gp, None)
        else:
            key = ka[0]
            cls = key.split("|")[0]
            jr, pr = ka[1], kp[1]
            # An object repeated across entries (every entry re-emits the
            # codespaces preamble) is legitimate; the same key carrying
            # different content within one side, or a different number of copies
            # between the sides, is not.
            if len(jr) != 1 or len(pr) != 1:
                stats["dup_key"] += 1
                cls_stage[cls]["dup_key"] += 1
                if len({tuple(r[1:5]) for r in jr}) > 1:
                    stats["intra_java_variance"] += 1
                if len({tuple(r[1:5]) for r in pr}) > 1:
                    stats["intra_py_variance"] += 1
                if len(jr) != len(pr):
                    stats["copies_differ"] += 1
                    cls_stage[cls]["copies_differ"] += 1
            st = stage_of(jr[0], pr[0])
            stats[st] += 1
            cls_stage[cls][st] += 1
            if st == "DIFF":
                diffs.write("%s\t%s\t%s\n" % (key, jr[0][5], pr[0][5]))
            elif st != "equal":
                absorbed.write("%s\t%s\n" % (key, st))
            ka = next(ga, None)
            kp = next(gp, None)
    for f in (only_j, only_p, diffs, absorbed):
        f.close()
    ja.close()
    py.close()
    with open(os.path.join(a.out, "join-summary.json"), "w") as f:
        json.dump({"totals": dict(stats),
                   "by_class": {k: dict(v) for k, v in sorted(cls_stage.items())}},
                  f, indent=1)
    print(json.dumps(dict(stats), indent=1))


# --------------------------------------------------------------------------
# census diff
# --------------------------------------------------------------------------

def load_tsv(p):
    d = {}
    with open(p) as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            d[tuple(parts[:-1])] = int(parts[-1])
    return d


def cmd_census(a):
    for name in ("census_e", "census_a", "census_z", "census_v",
                 "shell_e", "shell_a"):
        j = load_tsv(os.path.join(a.out, "java", name + ".tsv"))
        p = load_tsv(os.path.join(a.out, "py", name + ".tsv"))
        keys = sorted(set(j) | set(p))
        outp = os.path.join(a.out, name + "_diff.tsv")
        n = 0
        with open(outp, "w") as f:
            for k in keys:
                jv, pv = j.get(k, 0), p.get(k, 0)
                if jv != pv:
                    f.write("%s\t%d\t%d\t%+d\n" % ("\t".join(k), jv, pv, jv - pv))
                    n += 1
        print("%-10s keys java=%d py=%d differing=%d -> %s"
              % (name, len(j), len(p), n, outp))


# --------------------------------------------------------------------------
# extract: second pass, canonical form of named objects
# --------------------------------------------------------------------------

def cmd_extract(a):
    wanted = set()
    with open(a.keys) as f:
        for line in f:
            wanted.add(line.rstrip("\n").split("\t")[0])
    stamp = detect_stamp(a.zip)
    z = zipfile.ZipFile(a.zip)
    entries = sorted(i.filename for i in z.infolist())
    if a.only:
        pats = a.only.split(",")
        entries = [e for e in entries if any(e.startswith(p) for p in pats)]
    out = open(a.outfile, "w", buffering=1 << 20)
    hit = 0
    ce, ca, cz, cv = {}, {}, {}, {}
    canon = Canon(stamp, ce, ca, cz, cv)
    for entry in entries:
        fh = z.open(entry)
        stack = []
        depth = 0
        in_obj = 0
        for ev, node in ET.iterparse(fh, events=("start", "end")):
            if ev == "start":
                if in_obj:
                    stack.append((node, None, False))
                    depth += 1
                    continue
                local = node.tag.rpartition("}")[2]
                pl = stack[-1][1] if stack else None
                objroot = (pl is not None and is_container(pl)
                           and local[:1].isupper() and not local.endswith("Frame"))
                stack.append((node, local, objroot))
                depth += 1
                if objroot:
                    in_obj = depth
                continue
            elem, local, objroot = stack.pop()
            depth -= 1
            if in_obj and depth >= in_obj:
                continue
            if objroot:
                in_obj = 0
                oid = elem.get("id")
                ver = elem.get("version") or ""
                if ver == stamp:
                    ver = "@STAMP@"
                key = "%s|%s|%s" % (local, oid, ver)
                if key in wanted:
                    hs = canon.run(elem, local, want_text=True)
                    out.write("### %s\t%s\t%s\n" % (key, entry, "\t".join(hs)))
                    out.write("".join(canon.textbuf)
                              .replace("\x01", "\n<").replace("\x02", " ")
                              .replace("\x03", "=").replace("\x04", " TEXT:")
                              .replace("\x05", ">").replace("\x06", "</>")
                              + "\n")
                    hit += 1
                    if a.limit and hit >= a.limit:
                        out.close(); z.close()
                        print("[extract] %d objects (limit)" % hit); return
                if stack:
                    del stack[-1][0][:]
            else:
                if stack:
                    del stack[-1][0][:]
        fh.close()
    out.close()
    z.close()
    print("[extract] %d objects" % hit)


# --------------------------------------------------------------------------
# shell comparison
# --------------------------------------------------------------------------

def cmd_shell(a):
    jd = os.path.join(a.out, "java", "shell")
    pd = os.path.join(a.out, "py", "shell")
    names = sorted(set(os.listdir(jd)) & set(os.listdir(pd)))
    if a.only:
        pats = a.only.split(",")
        names = [n for n in names if any(n.startswith(p) for p in pats)]
    for n in names:
        jl = [l for l in open(os.path.join(jd, n)) if l[1:2] != "O"]
        pl = [l for l in open(os.path.join(pd, n)) if l[1:2] != "O"]
        same = jl == pl
        # object key sequence
        jk = [l.split(" ", 1)[1].strip() for l in open(os.path.join(jd, n)) if l[1:2] == "O"]
        pk = [l.split(" ", 1)[1].strip() for l in open(os.path.join(pd, n)) if l[1:2] == "O"]
        ms_same = Counter(jk) == Counter(pk)
        pos = sum(1 for x, y in zip(jk, pk) if x != y)
        print("%-24s shell_struct=%s lines=%d/%d  members=%d/%d multiset=%s order_mismatch_pos=%d"
              % (n.replace(".txt", ""), "SAME" if same else "DIFF", len(jl), len(pl),
                 len(jk), len(pk), "SAME" if ms_same else "DIFF", pos))
        if not same and a.verbose:
            import difflib
            d = list(difflib.unified_diff(jl, pl, "java", "py", n=1))
            sys.stdout.writelines(d[:a.verbose])


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("scan")
    s.add_argument("--zip", required=True)
    s.add_argument("--side", required=True, choices=("java", "py"))
    s.add_argument("--out", required=True)
    s.add_argument("--workers", type=int, default=4)
    s.add_argument("--only", default=None)
    s.set_defaults(fn=cmd_scan)

    s = sub.add_parser("join")
    s.add_argument("--java", required=True)
    s.add_argument("--py", required=True)
    s.add_argument("--out", required=True)
    s.set_defaults(fn=cmd_join)

    s = sub.add_parser("census")
    s.add_argument("--out", required=True)
    s.set_defaults(fn=cmd_census)

    s = sub.add_parser("shell")
    s.add_argument("--out", required=True)
    s.add_argument("--only", default=None)
    s.add_argument("--verbose", type=int, default=0)
    s.set_defaults(fn=cmd_shell)

    s = sub.add_parser("extract")
    s.add_argument("--zip", required=True)
    s.add_argument("--keys", required=True)
    s.add_argument("--outfile", required=True)
    s.add_argument("--only", default=None)
    s.add_argument("--limit", type=int, default=0)
    s.set_defaults(fn=cmd_extract)

    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
