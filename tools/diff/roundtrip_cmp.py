#!/usr/bin/env python3
"""Round-trip fidelity comparator.

One implementation of the invariant, applied to all three arms, so both languages are
judged by the same instrument:

    Mode A0 (Java only)  source element  vs  the fragment the loader CAPTURED at parse time
    Mode A1 (Java only)  source element  vs  the blob ACTUALLY IN THE DB after the full
                                             netex_to_db chain (load + resolve + resolveEmbeddings)
    Mode Bj (Java)       source element  vs  the RE-MARSHALLED bean
    Mode Bp (Python)     source element  vs  MyXmlSerializer over the loaded object

A0 vs A1 is the split that matters: A0 measures the capture path, A1 measures what downstream
reads.  resolve() repairs a reference's version/class by rewriting the WHOLE blob through
FragmentCodec.encode, so a class that resolve() touches loses its verbatim capture even though
the capture itself was perfect.  A0 pass + A1 fail localises the loss to resolve(); A0 fail
localises it to the capture.  The reference arm stores pickles and has no A0/A1, so Mode B is
the only arm the two languages share.

The invariant is NO-LOSS, not equality.  Both loaders denormalise (id / version /
dataSourceRef / responsibilitySetRef / srsName), so the output may legally contain MORE than
the source.  So:

  * every element, attribute and text value in the SOURCE appears in the OUTPUT with the same
    value  -- a shortfall is a LOSS;
  * anything the output ADDS must be one of the enumerated denormalisation arms -- an addition
    outside that allow-list is a finding in its own right.

Everything is compared as TEXT.  Values are never parsed: "17:10:00+01:00" and "17:10:00" are
the same instant but not the same data.

DECLARED NORMALISATIONS (each one counted, so what it absorbs is measurable):
  N-prefix  namespace PREFIX choice is ignored; elements/attributes are keyed by {uri}local.
            A wrong URI is caught, a renamed prefix is not.  Counted per side as the census
            of xmlns declarations.
  N-attr    attribute ORDER within a start tag.
  N-order   ORDER of sibling elements (the fact multiset is order-free).
  N-ws      text nodes that are entirely whitespace AND sit between child elements.  Leaf text
            is NEVER stripped, so <X>   </X> and <X/> stay distinguishable.
  N-empty   <X/> and <X></X> are the same fact (the parser cannot tell them apart).

Usage:
    rtcmp.py cmp  --dir <workdir> --feed <feedname> [--out <resultdir>]
    rtcmp.py agg  --results <resultdir> --out <file.json>
"""
import argparse
import base64
import gzip
import json
import os
import re
import sys
from collections import Counter, defaultdict
from xml.etree import ElementTree as ET

NETEX = "http://www.netex.org.uk/netex"
SHORT = {
    NETEX: "",
    "http://www.opengis.net/gml/3.2": "gml:",
    "http://www.siri.org.uk/siri": "siri:",
    "http://www.w3.org/2001/XMLSchema-instance": "xsi:",
    "http://www.w3.org/2001/XMLSchema": "xsd:",
}

# The denormalisation arms: attributes a loader is allowed to ADD.
ALLOWED_ADDED_ATTRS = {"id", "version", "dataSourceRef", "responsibilitySetRef", "srsName"}

WS_RE = re.compile(r"^\s*$")
NSDECL_RE = re.compile(rb'xmlns(?::([A-Za-z0-9_.-]+))?\s*=\s*"([^"]*)"')
MAX_SAMPLES = 3
VALUE_CLIP = 200


def qn(tag):
    if tag.startswith("{"):
        uri, local = tag[1:].split("}", 1)
        return SHORT.get(uri, "{%s}" % uri) + local
    return tag


def facts(xml_bytes, stats):
    """Multiset of (kind, path, name, value) facts, plus the two order-sensitive canonical forms.

    Returns (Counter, h_strict, h_attrsorted) where the two canonical strings differ only in
    whether attributes are emitted in document order or sorted -- that is how N-attr's
    absorption is counted; the Counter itself additionally absorbs N-order.
    """
    root = ET.fromstring(xml_bytes)
    c = Counter()
    strict = []
    sortd = []

    def walk(el, path):
        tag = qn(el.tag)
        p = path + "/" + tag if path else tag
        c[("E", p, "", "")] += 1
        strict.append("<" + p)
        sortd.append("<" + p)
        items = list(el.attrib.items())
        for k, v in items:
            n = qn(k)
            c[("A", p, n, v)] += 1
            strict.append(" %s=%s" % (n, v))
        for ak, av in sorted(items, key=lambda kv: qn(kv[0])):
            sortd.append(" %s=%s" % (qn(ak), av))
        kids = list(el)
        if not kids:
            t = el.text if el.text is not None else ""
            c[("T", p, "", t)] += 1
            strict.append(">" + t)
            sortd.append(">" + t)
        else:
            pieces = []
            for t in [el.text] + [k.tail for k in kids]:
                if t is None:
                    continue
                if WS_RE.match(t):
                    stats["ws_dropped"] += 1
                else:
                    pieces.append(t)
            if pieces:
                t = "".join(pieces)
                c[("T", p, "", t)] += 1
                strict.append(">" + t)
                sortd.append(">" + t)
            strict.append(">")
            sortd.append(">")
            for k in kids:
                walk(k, p)
        strict.append("</")
        sortd.append("</")

    walk(root, "")
    return c, hash("".join(strict)), hash("".join(sortd))


def nsdecl_census(xml_bytes):
    return Counter(
        (m.group(1).decode() if m.group(1) else "", m.group(2).decode())
        for m in NSDECL_RE.finditer(xml_bytes)
    )


class Agg:
    """Per-(mode, slot, verdict) aggregation. slot = (kind, path, name)."""

    def __init__(self):
        self.rows = defaultdict(lambda: {"n": 0, "objs": 0, "feeds": Counter(), "samples": []})
        self.counters = Counter()

    def add(self, mode, kind, path, name, verdict, n, feed, sample):
        r = self.rows[(mode, kind, path, name, verdict)]
        r["n"] += n
        r["objs"] += 1
        r["feeds"][feed] += 1
        if len(r["samples"]) < MAX_SAMPLES:
            r["samples"].append(sample)


def compare(mode, src_c, out_c, key, feed, agg):
    """Directional no-loss check plus the addition audit, for one object."""
    deficit = src_c - out_c          # in SOURCE, missing from OUTPUT  -> loss
    surplus = out_c - src_c          # in OUTPUT, absent from SOURCE   -> addition
    if not deficit and not surplus:
        return False
    dslots = defaultdict(list)
    sslots = defaultdict(list)
    for (kind, path, name, val), n in deficit.items():
        dslots[(kind, path, name)].append((val, n))
    for (kind, path, name, val), n in surplus.items():
        sslots[(kind, path, name)].append((val, n))
    for slot in set(dslots) | set(sslots):
        kind, path, name = slot
        d = dslots.get(slot)
        s = sslots.get(slot)
        if d and s:
            verdict = "CHANGED"
            n = sum(x[1] for x in d)
            sample = [key, feed, d[0][0][:VALUE_CLIP], s[0][0][:VALUE_CLIP]]
        elif d:
            verdict = "MISSING"
            n = sum(x[1] for x in d)
            sample = [key, feed, d[0][0][:VALUE_CLIP], ""]
        else:
            allowed = kind == "A" and name in ALLOWED_ADDED_ATTRS
            verdict = "ADDED-ARM" if allowed else "ADDED-UNEXPECTED"
            n = sum(x[1] for x in s)
            sample = [key, feed, "", s[0][0][:VALUE_CLIP]]
        agg.add(mode, kind, path, name, verdict, n, feed, sample)
    return True


# ------------------------------------------------------------------ row readers


def rows(path, ncols):
    """Yield (streamIdx, seq, cols...) grouped by label in order of first appearance."""
    if not os.path.exists(path):
        return
    last = None
    idx = -1
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) < ncols:
                continue
            if p[0] != last:
                last = p[0]
                idx += 1
            yield (idx, int(p[1])) + tuple(p[2:ncols])


def zip_aligned(streams, names, agg, feed):
    """Positional co-iteration over N document-ordered row streams, with windowed resync.

    All arms emit one row per interesting element in document order, so alignment is the
    identity; drift (a loader dropping or adding an object) is a FINDING, counted and reported
    rather than repaired.
    """
    its = [iter(s) for s in streams]
    cur = []
    for it in its:
        cur.append(next(it, None))
    while any(c is not None for c in cur):
        if any(c is None for c in cur):
            for i, c in enumerate(cur):
                if c is not None:
                    agg.counters["align.tail_only_%s" % names[i]] += 1
            break
        keys = [(c[0], c[2]) for c in cur]  # (streamIdx, localName)
        if len(set(keys)) == 1:
            yield cur
            cur = [next(it, None) for it in its]
            continue
        # drift: advance whichever arm is behind, up to a small window
        agg.counters["align.drift"] += 1
        minstream = min(c[0] for c in cur)
        for i, c in enumerate(cur):
            if c[0] == minstream:
                agg.counters["align.skip_%s" % names[i]] += 1
                cur[i] = next(its[i], None)


# ------------------------------------------------------------------ per-feed compare


def cmp_feed(workdir, feed, outdir):
    agg = Agg()
    src = rows(os.path.join(workdir, feed + ".src.tsv.gz"), 6)
    jav = rows(os.path.join(workdir, feed + ".java.tsv.gz"), 7)
    have_a1 = os.path.exists(os.path.join(workdir, feed + ".a1.tsv.gz"))
    have_py = os.path.exists(os.path.join(workdir, feed + ".py.tsv.gz"))
    streams = [src, jav]
    names = ["src", "java"]
    if have_a1:
        streams.append(rows(os.path.join(workdir, feed + ".a1.tsv.gz"), 6))
        names.append("a1")
    if have_py:
        streams.append(rows(os.path.join(workdir, feed + ".py.tsv.gz"), 6))
        names.append("py")
    stats = Counter()
    nsc = {"src": Counter(), "A0": Counter(), "A1": Counter(), "Bj": Counter(), "Bp": Counter()}

    for cur in zip_aligned(streams, names, agg, feed):
        s = cur[0]
        j = cur[1]
        a1 = cur[names.index("a1")] if have_a1 else None
        p = cur[names.index("py")] if have_py else None
        key = "%s|%s|%s" % (j[2], j[3], j[4])
        sxml = base64.b64decode(s[5])
        agg.counters["objects"] += 1
        try:
            sc, sh0, sh1 = facts(sxml, stats)
        except ET.ParseError:
            agg.counters["parse_error.src"] += 1
            continue
        nsc["src"].update(nsdecl_census(sxml))

        for mode, blob in (("A0", j[5]), ("A1", a1[5] if a1 else ""),
                           ("Bj", j[6]), ("Bp", p[5] if p else "")):
            if not blob:
                agg.counters["absent.%s" % mode] += 1
                continue
            xml = base64.b64decode(blob)
            try:
                oc, oh0, oh1 = facts(xml, stats)
            except ET.ParseError:
                agg.counters["parse_error.%s" % mode] += 1
                continue
            nsc[mode].update(nsdecl_census(xml))
            agg.counters["seen.%s" % mode] += 1
            # The JAXB re-marshal fingerprint: auto-generated ns2/ns3 prefixes, counted per
            # class so "which classes lost their verbatim capture" is measurable.
            if mode == "A1":
                if b"xmlns:ns2=" in xml or b"xmlns:ns3=" in xml:
                    agg.counters["remarshalled.%s" % j[2]] += 1
                else:
                    agg.counters["verbatim.%s" % j[2]] += 1
            if sh0 == oh0:
                agg.counters["rung0_strict.%s" % mode] += 1
            elif sh1 == oh1:
                agg.counters["rung1_attrorder.%s" % mode] += 1
            elif sc == oc:
                agg.counters["rung2_siborder.%s" % mode] += 1
            if sc == oc:
                agg.counters["identical.%s" % mode] += 1
            else:
                diff = compare(mode, sc, oc, key, feed, agg)
                if diff:
                    agg.counters["differs.%s" % mode] += 1

    agg.counters.update({"ws_dropped": stats["ws_dropped"]})
    os.makedirs(outdir, exist_ok=True)
    out = {
        "feed": feed,
        "counters": dict(agg.counters),
        "ns": {k: {"%s=%s" % kv: n for kv, n in v.items()} for k, v in nsc.items()},
        "rows": [
            {
                "mode": k[0], "kind": k[1], "path": k[2], "name": k[3], "verdict": k[4],
                "n": v["n"], "objs": v["objs"],
                "feeds": dict(v["feeds"]), "samples": v["samples"],
            }
            for k, v in agg.rows.items()
        ],
    }
    with gzip.open(os.path.join(outdir, feed + ".res.json.gz"), "wt", encoding="utf-8") as f:
        json.dump(out, f)
    return out


# ------------------------------------------------------------------ aggregate


def aggregate(resdir, outfile):
    counters = Counter()
    ns = defaultdict(Counter)
    rows_ = defaultdict(lambda: {"n": 0, "objs": 0, "feeds": Counter(), "samples": []})
    feeds = 0
    for fn in sorted(os.listdir(resdir)):
        if not fn.endswith(".res.json.gz"):
            continue
        feeds += 1
        with gzip.open(os.path.join(resdir, fn), "rt", encoding="utf-8") as f:
            d = json.load(f)
        counters.update(d["counters"])
        for side, c in d["ns"].items():
            ns[side].update(c)
        for r in d["rows"]:
            k = (r["mode"], r["kind"], r["path"], r["name"], r["verdict"])
            t = rows_[k]
            t["n"] += r["n"]
            t["objs"] += r["objs"]
            t["feeds"].update(r["feeds"])
            for s in r["samples"]:
                if len(t["samples"]) < MAX_SAMPLES:
                    t["samples"].append(s)
    out = {
        "feeds": feeds,
        "counters": dict(counters),
        "ns": {k: dict(v) for k, v in ns.items()},
        "rows": sorted(
            [
                {
                    "mode": k[0], "kind": k[1], "path": k[2], "name": k[3], "verdict": k[4],
                    "n": v["n"], "objs": v["objs"], "nfeeds": len(v["feeds"]),
                    "feeds": dict(v["feeds"].most_common(6)), "samples": v["samples"],
                }
                for k, v in rows_.items()
            ],
            key=lambda r: -r["n"],
        ),
    }
    with open(outfile, "w") as f:
        json.dump(out, f, indent=1)
    return out


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("cmp")
    c.add_argument("--dir", required=True)
    c.add_argument("--feed", required=True)
    c.add_argument("--out", required=True)
    a = sub.add_parser("agg")
    a.add_argument("--results", required=True)
    a.add_argument("--out", required=True)
    args = ap.parse_args()
    sys.setrecursionlimit(10000)
    if args.cmd == "cmp":
        r = cmp_feed(args.dir, args.feed, args.out)
        print(json.dumps(r["counters"], sort_keys=True))
    else:
        r = aggregate(args.results, args.out)
        print(json.dumps(r["counters"], sort_keys=True, indent=1))


if __name__ == "__main__":
    main()
