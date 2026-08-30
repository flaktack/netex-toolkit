# netex-toolkit

A Java toolkit for reading, transforming and exporting [NeTEx](https://netex-cen.eu/) at national scale. It loads a feed
into an embedded LMDB store that keeps every object as the **XML bytes the feed shipped**, transforms it there, and
writes NeTEx back out — including a full EPIP export profile. The design point is fidelity: nothing is silently
re-serialised, and what a build could and could not do is recorded beside the data.

# Features

- **Fragment storage.** Objects are stored as their source XML fragments, LZ4-compressed.
- **A generated, self-contained NeTEx model.**
- **Five driver sandwiches** — Allows writing custom scripts for feed→store, store→store, N stores→store, in-place,
  store→zip (`toolkit.harness`).
- **The EPIP export profile** (`toolkit.transform.epip`).
- **Refuse-or-tolerate is a policy object**, `toolkit.util.Strictness` has seven named concerns, and every refusal
  message names the knob that turns it off.
- **Provenance beside the data.** `.provenance.json` records what the writing jar was *capable* of;
  `.loadreport.json` records what one run *tolerated*.
- **Configuration declared once.** Every knob lives in `toolkit.util.Conf` with a name, type, default and one-line doc.
- **A fixture kit, shipped in the production jar on purpose.** `toolkit.test` gives you a real temp LMDB store, a NeTEx
  skeleton and a loader in one call, canonical XML comparison, reflective test discovery and three assertions — no JUnit,
  no runner, no build step. It is what lets you test a driver you wrote yourself.
- **The reference graph as a first-class structure.** `toolkit.graph` is an immutable forward + inverse CSR adjacency
  over the store's edges, built lazily and invalidated by any edge mutation.
- **CRS reprojection** (`toolkit.geo`, on proj4j), including the OGC-URN reduction and the axis-order handling the EPSG
  database forces. *Which* classes get reprojected is profile policy and lives a tier up.
- **A source-launched tier.** `scripts/` and `tools/` are JEP-458 programs — edit and run, no build step. See [
  `tools/README.md`](tools/README.md).

## Prior Art

- [badger](https://github.com/MMTIS/badger)

# Usage

The thin jar alone is not runnable — the drivers are uncompiled source programs launched against the jar plus `deps/`:

```
java -Xmx6g \
  --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -cp "toolkit/target/netex-toolkit.jar:toolkit/target/deps/*" \
  scripts/NetexToDb.java feed.xml.gz out.lmdb
```

**Those two flags are warning suppression, not correctness.** Measured on JDK 25: the store loads, resolves and reads
with no flags at all — omitting them costs eight lines of stderr per JVM, which is why every invocation here carries
them. They silence jffi's `System::load` and `Unsafe::putLong`, plus lz4-java's `System::loadLibrary`, which is a
second native caller unrelated to lmdbjava. An embedder wanting clean logs wants them too.

`--sun-misc-unsafe-memory-access` stops being cosmetic on JDK 26, where [JEP 498](https://openjdk.org/jeps/498) made
`deny` the default and jffi's `putLong` would throw without it.

This set used to carry four `--add-opens` (`java.nio`, `sun.nio.ch`, `java.lang`, `java.lang.reflect`) described as
mandatory. They were measured and found dead: those are `ByteBufferProxy`'s requirement, and `AbstractLmdbStore` builds
its `Env` on `ByteArrayProxy.PROXY_BA`, so that class never loads. Do not add them back without a run that fails
without them.

With the shaded jar only the classpath changes — same flags, same drivers:

```
  -cp toolkit/target/netex-toolkit-shaded.jar \
```

**If you call the library directly** rather than through a driver, two rules are not optional and are otherwise only in
the javadoc:

- A load is not finished until `resolve()` **and** `resolveEmbeddings()` have run. The harnesses do this for you;
  `NetexLoader`/`ZipLoad` on their own do not.
- `resolve()` opens and commits its own write transaction, so it must never be called under an open
  `rwTxn`.

## Configuration and knobs

Values come from, highest first:

1. `-Dtoolkit.<name>=<value>` on the command line
2. a config file: `-Dtoolkit.conf=<path>`, else `./toolkit.conf` if it exists
3. the declared default

The file is `java.util.Properties` format, keyed by the knob name without the `toolkit.` prefix:

```properties
# toolkit.conf
refPatch=true
strict.remarshal=true
load.zipWorkers=4
```

**A typo is refused rather than ignored.** A file key naming no knob fails the run and names the knob you probably
meant; a `-Dtoolkit.*` naming no knob is warned about by name (and refused under
`-Dtoolkit.strict=true`); a malformed value fails instead of silently reading as off. Booleans accept
`true|false|on|off|yes|no|1|0` and a bare `-Dtoolkit.foo`.

**Every run logs one `[conf]` line** naming the file it read and every value that is not at its default, so a transcript
records what the run was actually configured with.

The authoritative list is `toolkit.util.Conf` — one table, with a doc line per knob, and
`toolkit/src/test/java/toolkit/util/TestConf.java` fails the build if anything starts reading a system property
directly. By family:

| family           | for example                                                                | what it reaches                                                                        |
|------------------|----------------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| `strict.*`       | `strict.remarshal`, `strict.emptyOutput`                                   | the seven refuse-or-tolerate concerns; `-Dtoolkit.strict=true` makes all of them fatal |
| store            | `refPatch`, `inventNameOfRefClass`, `embIndex`, `resolve.*`, `blobSweep.*` | reference repair, the embedding index, the resolve sweeps                              |
| load             | `load.zipWorkers`, `load.zipTxnBatch`, `load.profile`                      | zip parallelism, transaction batching, load profiling                                  |
| export / profile | `epip.*`                                                                   | EPIP generator parallelism and its peek/index fast paths                               |
| provenance       | `prov`, `prov.maxWriters`                                                  | sidecar stamping and how much lineage it keeps                                         |
| format / runtime | `v2.blockPairs`, `store.growChunk`, `jaxbPin`                              | on-disk block size, array growth policy, the JAXB allocation fix                       |

**Some knobs decide the provenance stamp.** A knob can *withhold* a capability:
`-Dtoolkit.refPatch=false` takes the whole-blob re-marshal path, so a store built under it no longer claims
`no-remarshal-resolve`. The sidecar tracks the configuration, not the build — see
[Storage](#storage).

## Harnesses / Examples

The drivers in `scripts/` are thin CLI sandwiches over library entry points, and they are the worked examples for
writing your own. The transformation they drive is compiled, under
`toolkit.transform.*`. `scripts/` is flat and in the **default package** — one directory, no
`conv/` or `fix/` split — and every sandwich has at least one example, which
`TestHarnessExamples` fails the build over if it stops being true:

| driver              | sandwich    | what it shows                                                                                                                                          |
|---------------------|-------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| `NetexToDb`         | `FeedToDb`  | a feed into a fresh store, on the default load options                                                                                                 |
| `TrenitaliaDbToDb`  | `DbToDb`    | the same sandwich actually transforming: copy every class but one, re-emit that one with synthesised `TrainNumber`s interleaved before their referrers |
| `TrenitaliaInPlace` | `InPlace`   | that transform over a store that already exists — reading and writing one class map through ONE transaction                                            |
| `DbToDbCopy`        | `DbToDb`    | one store into a fresh one; `copyMap` so the "wrote N" tally is real                                                                                   |
| `MergeDbToDb`       | `MergeToDb` | N stores into one, first source winning collisions                                                                                                     |
| `DbToZipRaw`        | `DbToZip`   | a store out to a zip, one entry per class, fragments verbatim                                                                                          |

The two Trenitalia drivers are the same transform on both transform sandwiches, and they are the reason
`toolkit.transform.common.TrainNumbers` is compiled at all: a source-launched driver can reach nothing but the jar, so
an example transform has to be in it.

At one extreme a driver is the harness call and nothing else — this is the whole of
`NetexToDb`, the most-used loader in the chain:

```java
public static void main(String[] args) throws Exception {
    FeedToDb.driverMain("netex-to-db", args, ZipLoad.Options.DEFAULTS);
}
```

At the other, `DbToZipRaw` supplies twelve lines inside the sandwich — `DbToZip` owns the CLI, the read-only open, the
transaction, the atomic `.tmp`/rename and the reporting tail; `FrameWriter`
decompresses and emits; the driver decides only what goes in which entry:

```java
DbToZip.driverMain("db-to-zip-raw", args, USAGE, new String[0], a -> (db, tx, out) -> {
    NetexEnvelope env = new NetexEnvelope(ENVELOPE, List.of(), null);
    FrameWriter fw = new FrameWriter(db, tx, out, env); // no hook: stored bytes, verbatim
    for (Class<?> clazz : db.dbNames(tx)) {
        if (!fw.hasRows(clazz)) continue;
        ...
    }
});
```

Two drivers are deliberately **not** minimal. `EpipDbToXml` (~800 lines) is the EPIP export **profile** sitting on the
same `DbToZip` sandwich `DbToZipRaw` shows in fifty — the frames, the order and the reference closure are the hard part,
and that is what those lines are. `EpipDbToDb`
drives `EpipToDb`, a phase composition, rather than a sandwich directly. Read the six above first.

**Your drivers cannot live here, and that is forced rather than chosen.** A source-launched program gets exactly one
source root, derived from the launched file's own path and package, and `java` has no `--source-path` to override it —
so a driver in another repository cannot import a `.java` from this one. Reach everything through the jar. The cost is
real: a change to a compiled generator is no longer edit-and-run for a downstream consumer, which needs `mvn install`
first.

## Instruments

Nothing in [`tools/`](tools/README.md) ships, and `mvn verify` runs none of it. Two you will want:

- **`tools/inspect/nx`** — one entry point, one subcommand each: `db` (store browser), `bind` (the silent-null sweep —
  values that failed to bind and left a field empty without raising anything),
  `geo`, `lens`, `xsd` (XSD-validate an export zip), `prov` (read the sidecar).
- **`tools/diff`** — the before/after loop for "did my change alter the data?". `diff/Fragments.java`
  is the default store check (order-independent digest, so an insert-order change does not trip it);
  `diff/export_zip.py` is the default export check.

Each tool's own header records what that instrument **cannot** see as carefully as what it can;
`diff/StoreNames.java` is the model. `tools/README.md` exists to get you to the right header.

# Build

Requires a JDK 25 and Maven.

```
mvn -B -ntp verify
```

That is the whole build: `netex-model` codegen and its JUnit tests, then the toolkit jar,
`toolkit/target/deps/`, `toolkit/target/netex-toolkit-shaded.jar`, and the script test suite.

Two artefacts come out of `package`, and they are the same code in two shapes:

| artefact                      | what it is                                                                                                                      |
|-------------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `netex-toolkit.jar` + `deps/` | the thin jar and its 31 dependencies as loose files. What `tools/gate/compile.sh` and the source-launched drivers run on        |
| `netex-toolkit-shaded.jar`    | the same classes with every dependency bundled in. One file, no `deps/` to keep in step. What a downstream consumer should take |

CI publishes both; the shaded one as the **`netex-toolkit-shaded.jar`** artifact.

**`mvn verify` runs both suites**, and both are surefire's: `netex-model`'s JUnit tests and the toolkit's ~335 in
`toolkit/src/test/java`, each file in the package of whatever it tests. One file:

```
mvn -pl toolkit test -Dtest=TestRefPatcher
```

They are JUnit by registration only. The assertions are still `toolkit.test.Check`, the fixtures still `TestStore`/
`Gen`/`Xml`, and a test method still just takes a `TestStore` — supplied by
`TestStoreExtension`, which is service-loaded so no class has to remember an annotation. **The fixture kit itself stays
JUnit-free and ships in the jar**; only that one extension is test-scoped. Surefire runs the whole suite in one JVM
(`forkCount=1`), which is the batching the old source-launched `AllTests` existed for — the fixed cost is JVM start and
JAXB context init, worth roughly 15x — and it passes the six JVM flags, without which lmdbjava fails inside the store.

**The build enforces more than compilation**, which explains a lot of the repository's shape: one gate asserts there is
exactly one NeTEx model on the runtime classpath and another that the model binds through no foreign adapter; javadoc
runs in reference-only doclint, so a broken `{@link}`
fails the build; `TestConf` refuses a direct system-property read; a gate asserts the accessor seam for the selected
schema version is complete; and `assert-shaded-jar-is-complete` is the only thing that exercises the shaded jar at all.
Each gate checks it is **operative** before reporting green — a gate that cannot tell "clean" from "nothing to look at"
is not a gate.

# Design notes

## XML processing quirks

Most of what this toolkit is came from a defect in how NeTEx meets JAXB. The ones worth knowing before you trust a
value:

- **Enum whitespace.** Every NeTEx enumeration restricts `xs:NMTOKEN`, whose whitespace facet is *collapse*, so
  `<TransportMode>bus </TransportMode>` and `bus` are the same value. XJC binds such a type with the default base
  `String`, and the RI looks the raw lexical up in a map keyed on exact text: a padded value misses and the RI returns
  **null, silently** — no exception, no
  `ValidationEvent`. The element then ceases to exist on the bean and in the next marshal.
  `EnumWhitespace` collapses the text before the RI sees it.
- **Date and time are carriers, not values.** `xs:time`/`xs:date`/`xs:dateTime`/`xs:duration` bind to lexical-preserving
  carriers, because the stock bindings drop the timezone offset, silently fold
  `24:00:00` to `00:00:00`, and reject the year/month duration forms the corpus is full of. Two consequences: **
  `hashCode()` throws on purpose** — never put a carrier in a `Set`, since two carriers naming the same instant can
  differ lexically — and unparseable input is *kept*, not dropped. JAXB cannot see the second case (the adapter returned
  an object, so nothing is raised), which is why there is a separate `noi.netex.time.UnparsedTally`.
- **`xs:normalizedString`.** Jakarta's adapter applies the whitespace facet and rewrites CR/LF/TAB to spaces, destroying
  the line breaks feeds put in addresses and descriptions.
  `VerbatimStringAdapter` replaces it and does nothing at all, deliberately.
- **Child-element order.** The store keeps the source's order and the exporter copies the bytes, so a schema-invalid
  source feed produces an export that fails `cvc-complex-type.2.4.a` — and no other gate sees it, because JAXB readers
  accept out-of-order children. `FragmentOrder` re-ranks top-level children into the declared XSD sequence by **moving
  spans**, never re-serialising. It declines (and counts) rather than guessing on comments, CDATA and PIs, and on
  children the class does not declare — substitution members, `gml:*` — which is the whole of its coverage gap.
- **Marshal-time omit-default.** An `@XmlList` field emits `<DaysOfWeek></DaysOfWeek>` when its list is non-null and
  empty, and xjc bakes XSD defaults into *getters* so a field acquires the value the moment anything copies
  getter→field. Both are normalised at the single chokepoint every mutated object passes through, by rules **derived**
  from a virgin instance rather than written down, so regenerating from a newer schema cannot leave a stale table
  behind. One case is uncoverable:
  `@XmlElement(defaultValue=…)` on a *list* field, where the divergence is created at unmarshal and an empty source is
  afterwards indistinguishable from one that spelled the default out.
- **2.0 redefines `MultilingualString` as a mixed type**, so `getValue()`/`setValue()` cease to exist and there is one
  state for null and empty — `<Name/>` reads null where simple content read
  `""`. Every call site goes through the `Mls` accessor seam.
- **Two StAX implementations, chosen once** (`Stax`). Woodstox where per-event character offsets are required, because
  that is what lets `RefPatcher` splice attributes into stored bytes; Aalto for decode, because it is faster.
  Equivalence is checked rather than assumed.
- **An element name does not determine a class.** Some NeTEx names are declared with different types in different
  parents, so `RefElementIndex` returns a candidate **set**; a caller must reach the same verdict for all of them or
  fall back to unmarshalling. Completeness is the safety property, not precision.
- **A NeTEx `ref` attribute is unqualified**, so a namespace-blind attribute read will happily match
  `gml:id` where `id` was meant.
- **`BindEvents` counts what failed to bind** — the `ValidationEventHandler` nobody installs — but read its note before
  trusting a zero: it cannot see a value whose *adapter* returned an object, which is exactly what the date/time
  carriers do.
- **One JAXB `Coordinator` sentinel is pinned per thread**, because the RI otherwise allocates a
  `ThreadLocalMap.Entry` on every single XML event. No output byte differs either way; worth knowing if you embed the
  library in your own threads.

## Storage

Stores are LMDB, and there is one format, `java-v2:1`. A retired `v1` layout stored pickled values instead of XML
fragments; its identifier survives only as `Stores.RETIRED_V1`, so that a store or a command line naming it fails with a
sentence explaining why rather than "unknown store format".

```
class maps    name = raw 2-byte LE class idx; key = 4-byte LE local key;
              value = the LZ4 length-prefixed source XML fragment
_id_idx       NulKeyCodec key -> 8-byte LE full_key
_meta         "format", "seq", and 'c'+2-byte-LE-idx rows -> class name (the class table)
_edge_blocks  chunked long[] blocks: [kind byte][4-byte LE block#] -> up to 65,536
              (referrer, target) pairs. kind 0 resolved, kind 1 unresolved
_unres_keys   interning table for the var-length unresolved target keys
_embedding_idx  DUPSORT/DUPFIXED, over the classes the profile's embedding scope names
```

- **Edges live inside LMDB**, not in a sidecar file: the stage artifact stays one directory a caller can move
  atomically, edge persistence commits in the same transaction as the objects (no torn-sidecar crash states), and a 1
  MiB value streams out of the mmap at memory speed anyway.
- **Deletes tombstone in place** rather than rewriting the index, so a delete costs one block, and a kind is compacted
  only once its dead slots pass a fraction of its total. Deletion is indexed by referrer, not scanned.
- **Key layout is one class**, `toolkit.keycodec`: `id NUL version NUL [class_idx LE16]` for the id index, and
  `(class_idx, local_key)` packed into the 8-byte `full_key` everything else passes around. Changing anything there
  changes every stored byte, which is why it is its own package.
- **The class table is written once, at creation.** A store built against a different model generation therefore cannot
  be a clone source for a chain. A corollary for tooling: a class map's LMDB *name* **is** its class index, so a
  byte-level comparator goes vacuous the moment numbering shifts — `tools/diff/StoreNames.java` is the one that survives
  renumbering.
- **The default map size is 64 GiB** and the drivers are run at `-Xmx4g`–`6g`.

**Two sidecar files sit beside the store**, answering two different questions and deliberately not merged.
`.provenance.json` is a property of the **code**: which behaviour-changing fixes the writing jar had. It intersects
across a merge — a merged store is only as capable as its least capable input — and a downstream stage can refuse an
input on it. `.loadreport.json` is a property of a **run over an input**: what it tolerated. Tolerating something is
only defensible if the toleration is legible afterwards, which is what the second file is for.

## Known limits

- **The export is not byte-reproducible.** The exporter writes a wall clock into
  `<PublicationTimestamp>`. Zip *entry metadata* is pinned to the DOS epoch and does compare, but anything that digests
  an export must normalise that field first — `tools/diff/export_zip.py`
  exists for exactly this.
- **Nothing is relocated in the shaded jar**, deliberately: `ModelRegistry` feeds literal package-name strings to
  `Class.forName` and `JAXBContext.newInstance`, so a relocation would rewrite them into names that fail at runtime
  rather than at build.
- **Every store written before one capability correction overstates its vector by one**, and provenance intersects, so a
  chain reading such a store carries the wrong claim forward. Nothing refuses on that name today.
- **Two counters are per-JVM**, not per-feed — correct for a driver that loads one feed per process, ambiguous for
  anything that loads several.
- **Single-threaded by contract**, not by defence: one `FragmentCodec` per thread, one `BindEvents`
  handler per unmarshaller. Only the static tallies are shared.
- **A source-launched program gets one source root**, so a driver in another repository cannot import a `.java` from
  this one and must reach everything through the jar.
- **The sidecar describes the configuration, not the build** — see
  [Configuration and knobs](#configuration-and-knobs).

# Licensing

**This repository is AGPL-3.0** — [`LICENSE`](LICENSE) carries the full, verbatim FSF text.