# tools

Instruments for answering "did my change alter the data?", "what is actually in this store?", and
"does everything still compile?". They are **uncompiled JEP-458 source programs** — edit and run,
no build step — launched against the built jar.

Nothing here is part of the shipped artifact. `mvn verify` does not run any of it.

## Which one do I want?

| question | tool |
|---|---|
| Did my change alter the **database**? | `diff/Fragments.java` |
| …and it may have renumbered the class table (anything touching `ModelRegistry`)? | `diff/StoreNames.java` — `Fragments`/`StoreBytes` cannot see past renumbering |
| Did my change alter the **export**? | `diff/export_zip.py` |
| It *did* change — **what** changed? | `diff/export_xml.py census`, or `diff/StoreBytes.java` |
| Is a stored fragment still faithful to the **source feed**? | `diff/RoundTrip.java` |
| What is actually **in** this store / object / export? | `inspect/nx` |
| Does everything outside Maven still **compile**? | `gate/compile.sh` |
| Is the **model** safe to swap? | `model/ModelGate.java` |

## Running them

Build first — the jar alone is not runnable, `deps/` is required:

```bash
mvn -B -ntp package
```

Then, once per shell:

```bash
P=$(git rev-parse --show-toplevel)
CP="$P/toolkit/target/netex-toolkit.jar:$P/toolkit/target/deps/*"
# Warning suppression, not correctness: on JDK 25 the store runs with no flags at all, but omitting
# these costs eight lines of stderr per JVM. See ../README.md#usage for what each one silences and
# for the four --add-opens that were measured dead and removed.
JVM="--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
```

Every Java row below is then `java $JVM -cp "$CP" tools/<path> <args>`. The Python tools are pure stdlib and need none
of this. `gate/*.sh` find their own paths.

## The before/after loop

Build the store and export on each side, then:

```bash
java $JVM -cp "$CP" tools/diff/StoreNames.java  before.lmdb after.lmdb
java $JVM -cp "$CP" tools/diff/Fragments.java   before.lmdb before.digest
java $JVM -cp "$CP" tools/diff/Fragments.java   after.lmdb  after.digest
diff before.digest after.digest
python3 tools/diff/export_zip.py before.zip after.zip
```

`diff/StoreNames.java --self-test` builds its own fixtures and checks seven arms — that it agrees
on a pure renumbering and bites on a real difference. Run it once before trusting a green.

## gate/ — build gates

| tool | lines | what it does |
|---|---|---|
| `gate/compile.sh` | 216 | javac over `tools/` and `scripts/` — every source tree outside the Maven modules. One javac per *implied* source root, roots derived from package declarations, files discovered by glob rather than listed. **The only thing that compiles `scripts/` at all** — the suite moved into the Maven module and no longer reaches the drivers. `conv/ObbToDb.java` and `conv/SwissToDb.java` were the files this mattered most for — no test reaches either — and they now live in `../noi-pipeline`, which gates its own tree with `scripts/compile-gate.sh`, a copy of this one. Exit 2 means the gate could not run, which is a failure and not a skip. |
| — | — | There is no suite runner here any more. The suite is `toolkit/src/test/java` and surefire's: `mvn -pl toolkit test`, or `-Dtest=TestX` for one file. `gate/suite.sh` ran the source-launched suite that used to live in `scripts/tests/`. |

## diff/ — before/after equivalence

None of these needs the national corpus: they take any two stores or any two zips, so they work
against fixture-scale data.

| tool | lines | what it does |
|---|---|---|
| `diff/Fragments.java` | 46 | **Default DB check.** Logical, *order-independent* digest of every stored fragment — `<class>\t<localKey>\t<sha256>`, sorted. Identical digest ⇒ byte-identical fragments for byte-identical keys. Insensitive to physical LMDB layout, so an insert-order change does not trip it. |
| `diff/StoreNames.java` | 704 | Store A/B keyed by class **name** rather than class index — the only comparator that survives a class-table renumbering. A v2 class map's LMDB dbi *name* **is** its 2-byte class index, so the two below go vacuous the moment numbering shifts (measured across the 2.0 flip: 33 differing maps, 16 "MISSING", identical row counts — all pure renumbering). Rewrites indices in `_id_idx`/`_embedding_idx`/`_edge_blocks`/`_unres_keys` into names, then hashes. Pure LMDB; names no model class, so one source runs against either model jar. Has `--self-test`. **Cannot see semantics — it is a storage comparator, not a meaning comparator.** |
| `diff/StoreBytes.java` | 98 | Physical LMDB byte-compare, map-by-map and key-by-key, printing the first differing rows. Reach for it when `Fragments` passes and something still looks wrong. *Vacuous if class numbering changed.* |
| `diff/StoreHash.java` | 101 | `StoreBytes --hash`: per-map SHA-256 only, for stores too large to diff row-by-row (20 GB+). |
| `diff/Graph.java` | 649 | Format-agnostic graph-equivalence dump of a store, as sorted lines. Compares reference *structure* rather than bytes. Shardable. |
| `diff/RoundTrip.java` | 520 | Per-element round-trip fidelity: source subtree vs captured fragment vs re-marshal vs the blob actually in the db. **The instrument for capture-fallback and re-marshal loss** — i.e. for the claim that stored objects are their source bytes. |
| `diff/roundtrip_cmp.py` | 401 | Comparator over two `RoundTrip` runs. |
| `diff/roundtrip_show.py` | 55 | Reader for `roundtrip_cmp` results. |
| `diff/roundtrip_tables.py` | 127 | Report tables from a `roundtrip_cmp` summary. |
| `diff/export_zip.py` | 15 | **Default export check.** Entry-by-entry zip compare, normalising `<PublicationTimestamp>` — the one field the exporter writes from a wall clock, and the reason an export is not byte-reproducible on its own. Exit 0 iff every entry matches. Pure stdlib. |
| `diff/export_xml.py` | 671 | Streaming, normalising semantic XML A/B: `scan` / `join` / `census` / `shell` / `extract`. Answers *what* changed when bytes change on purpose. Nothing is held in memory. |
| `diff/fragments_xml.py` | 80 | Canonical XML comparison of two directories of fragments — namespace-stripped tags, sorted attributes, element order preserved. Pure stdlib. |

## inspect/ — the `nx` debugging CLI

One entry point, one subcommand each: `java $JVM -cp "$CP" tools/inspect/nx/Nx.java <cmd>`.

| subcommand | file | lines | what it does |
|---|---|---|---|
| `db` | `DbCmd.java` | 1840 | Store browser: class maps, id index, edges, blobs. |
| `bind` | `BindCmd.java` | 1300 | **The silent-null sweep** — finds values that failed to bind and left a field empty without raising anything. |
| `geo` | `GeoCmd.java` | 2515 | Geometry extract and a self-contained map. |
| `lens` | `LensCmd.java` | 857 | One object rendered identically across every layer it can reach, with one verdict line per adjacent boundary. |
| `xsd` | `XsdCmd.java` | 725 | XSD-validates an export zip, aggregated to a baseline-and-diff ledger. |
| `prov` | `ProvCmd.java` | 174 | Reads the `.provenance.json` sidecar. |
| — | `BeanRender` `XmlFind` `XmlDoc` `RawStore` `BindProbe` `Nx` | 2417 | Support: bean rendering with declared-but-null fields shown, element lookup by `@id`, document-order XML model, raw v2 point access bypassing `V2Store`, the launcher. |

## model/ — model and format

| tool | lines | what it does |
|---|---|---|
| `model/ModelGate.java` | 1054 | Pre-flight probe for a model swap. **Reflection only** — imports no model or toolkit class, so one source runs unchanged against either model jar. Baselines in `base/*.tsv`. |
| `model/EnumFacetDiff.java` | 604 | Enumeration-facet diff across schema versions, with three verdicts. |
| `model/FmtDump.java` | 179 | Byte-level store dump for the on-disk format spec. |

## What is not here

About 110 campaign files were dropped rather than restored: per-task probes and chains
(`task75`, `task80`, `task88`, `task100`–`task162`), `checks/`, `tier1/`, `tier3/`, `verify89/`
and `scale/`.

They answered questions that are closed. Each was written to settle one decision — *"does the
coupling actually need 52 g?"*, *"where does the journey generator spend its wall?"* — and the
answer is in the commit that removed them, not in the file. Recover one with:

```bash
git ls-tree -r 277f543 --name-only tools/     # every path as it was
git show 277f543:tools/<path>                 # one file
```

## A note on reading these

Each tool's own header comment is its documentation, and several are better than this file — they
record what the instrument **cannot** see as carefully as what it can. `diff/StoreNames.java` is
the model. This README exists to get you to the right header, not to replace it.
