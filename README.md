# netex-toolkit

A Java toolkit for reading, transforming and exporting [NeTEx](https://netex-cen.eu/) at national scale. It loads a feed
into an embedded LMDB store that keeps every object as XML, transforms it there, and writes NeTEx back out — including a
full EPIP export profile.

# Features

- **Fragment storage.** Objects are stored as their source XML fragments, LZ4-compressed.
- **A generated, self-contained NeTEx model.**
- **Five driver sandwiches** — Allows writing custom scripts for feed→store, store→store, N stores→store, in-place,
  store→zip (`toolkit.harness`).
- **The EPIP export profile** (`toolkit.transform.epip`).
- **Refuse-or-tolerate is a policy object**, `toolkit.util.Strictness` has seven named concerns, and every refusal
  message names the knob that turns it off.
- **Configuration declared once.** Every knob lives in `toolkit.util.Conf` with a name, type, default and one-line doc.
- **Provenance beside the data.** `.provenance.json` records what the writing jar was *capable* of;
  `.loadreport.json` records what one run *tolerated*.
- **A fixture kit, shipped in the production jar on purpose.** `toolkit.test` gives you a real temp LMDB store, a NeTEx
  skeleton and a loader in one call, canonical XML comparison, reflective test discovery and three assertions — no
  JUnit, no runner, no build step. It is what lets you test a driver you wrote yourself.
- **The reference graph as a first-class structure.** `toolkit.graph` is an immutable forward + inverse CSR adjacency
  over the store's edges, built lazily and invalidated by any edge mutation.
- **CRS reprojection** (`toolkit.geo`, on proj4j), including the OGC-URN reduction and the axis-order handling the EPSG
  database forces. *Which* classes get reprojected is profile policy and lives a tier up.
- **A source-launched tier.** `scripts/` and `tools/` are JEP-458 programs — edit and run, no build step. See
  [`tools/README.md`](tools/README.md).

## Prior Art

- [badger](https://github.com/MMTIS/badger)

# Usage

The shaded jar alone is not runnable — the drivers are uncompiled source programs launched against the jar:

```
java -Xmx6g \
  --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -cp toolkit/target/netex-toolkit-shaded.jar \
  scripts/NetexToDb.java feed.xml.gz out.lmdb
```

Those two flags are warning suppression, not correctness: `--sun-misc-unsafe-memory-access` stops being cosmetic on JDK
26, where [JEP 498](https://openjdk.org/jeps/498) made `deny` the default and jffi's `putLong` would throw without it.

**If you call the library directly** rather than through a driver, two rules are not optional and are otherwise only in
the javadoc:

- A load is not finished until `resolve()` **and** `resolveEmbeddings()` have run. The harnesses do this for you;
  `NetexLoader`/`ZipLoad` on their own do not.
- `resolve()` opens and commits its own write transaction, so it must never be called under an open `rwTxn`.

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

The authoritative list is [`toolkit.util.Conf`](toolkit/src/main/java/toolkit/util/Conf.java). By family:

| family           | for example                                                                | what it reaches                                                                        |
|------------------|----------------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| `strict.*`       | `strict.remarshal`, `strict.emptyOutput`                                   | the seven refuse-or-tolerate concerns; `-Dtoolkit.strict=true` makes all of them fatal |
| store            | `refPatch`, `inventNameOfRefClass`, `embIndex`, `resolve.*`, `blobSweep.*` | reference repair, the embedding index, the resolve sweeps                              |
| load             | `load.zipWorkers`, `load.zipTxnBatch`, `load.profile`                      | zip parallelism, transaction batching, load profiling                                  |
| export / profile | `epip.*`                                                                   | EPIP generator parallelism and its peek/index fast paths                               |
| provenance       | `prov`, `prov.maxWriters`                                                  | sidecar stamping and how much lineage it keeps                                         |
| format / runtime | `v2.blockPairs`, `store.growChunk`, `jaxbPin`                              | on-disk block size, array growth policy, the JAXB allocation fix                       |

## Harnesses / Examples

The drivers in `scripts/` are thin CLI sandwiches over library entry points, and they are the worked examples for
writing your own. The transformation they drive is compiled, under `toolkit.transform.*`. `scripts/` is flat and in the
**default package** — one directory:

| driver              | sandwich    | what it shows                                                                                                                                          |
|---------------------|-------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| `NetexToDb`         | `FeedToDb`  | a feed into a fresh store, on the default load options                                                                                                 |
| `TrenitaliaDbToDb`  | `DbToDb`    | the same sandwich actually transforming: copy every class but one, re-emit that one with synthesised `TrainNumber`s interleaved before their referrers |
| `TrenitaliaInPlace` | `InPlace`   | that transform over a store that already exists — reading and writing one class map through ONE transaction                                            |
| `DbToDbCopy`        | `DbToDb`    | one store into a fresh one; `copyMap` so the "wrote N" tally is real                                                                                   |
| `MergeDbToDb`       | `MergeToDb` | N stores into one, first source winning collisions                                                                                                     |
| `DbToZipRaw`        | `DbToZip`   | a store out to a zip, one entry per class, fragments verbatim                                                                                          |

The two Trenitalia drivers are the same transform on both transform sandwiches. At one extreme a driver is the harness
call and nothing else — this is the whole of `NetexToDb`, the most-used loader in the chain:

```java
public static void main(String[] args) throws Exception {
    FeedToDb.driverMain("netex-to-db", args, ZipLoad.Options.DEFAULTS);
}
```

At the other, `DbToZipRaw` supplies a body inside the `DbToZip` sandwich, deciding only what goes in which entry:

```java
public static void main(String[] args) throws Exception {
    DbToZip.driverMain("db-to-zip-raw", args, USAGE, new String[0], a -> (db, tx, out) -> {
        NetexEnvelope env = new NetexEnvelope(ENVELOPE, List.of(), null);
        FrameWriter fw = new FrameWriter(db, tx, out, env);
        for (Class<?> clazz : db.dbNames(tx)) {
            if (!fw.hasRows(clazz)) continue;
            String name = clazz.getSimpleName();
            fw.entry(name + ".xml");
            env.frameOpen(out, "GeneralFrame", "RAW_" + name, "raw:GENERAL");
            out.write("<members>");
            fw.all(clazz);
            out.write("</members></GeneralFrame>");
            fw.end();
        }
    });
}
```

### Testing a driver

Testing one needs no JUnit, no runner and no build step: `toolkit.test` ships in the jar for exactly this.
`TestStore.create` is a temp LMDB store, a NeTEx skeleton and the real loader in one call, and everything the driver
touches comes off it — `buildDbFile(frames, name)` for a store input the driver opens itself (populated, resolved and
*closed*, which is what a driver expects to find), `tempPath(name)` for anything it writes, `loadNetex(frames)` to fill
the store. In all three, `frames` is the inner content of the skeleton's frames element, not a whole document. Then
assert by **reopening** what the driver wrote — `Stores.open(path, true)`, a `roTxn`, `iterOnlyObjects` — because that,
and not the driver's return value, is what the next stage in a chain will see:

```java
// MyDriverTest.java — beside MyDriver.java in your source root, launched the same way
public static void testEveryLineSurvives(TestStore ts) throws Exception {
    Path src = ts.buildDbFile("""
            <ServiceFrame id="sf" version="1"><lines>
              <Line id="line:1" version="1"><Name>L</Name></Line>
            </lines></ServiceFrame>""", "src.lmdb");
    Path out = ts.tempPath("out.lmdb");

    MyDriver.main(new String[]{src.toString(), out.toString()});   // the driver, whole

    try (Store db = Stores.open(out, true); Txn tx = db.roTxn()) {
        List<Object> lines = new ArrayList<>();
        for (Object o : db.iterOnlyObjects(tx, Line.class)) lines.add(o);
        Check.equals(1, lines.size(), "the transform must keep every Line");
        Xml.assertXmlEqual(Xml.toXmlAll(lines), EXPECTED);
    }
}

void main(String[] args) {
    Runner.run(MyDriverTest.class);
}
```

**Launch it exactly like the driver** — the [Usage](#usage) line with `scripts/NetexToDb.java` swapped for
`MyDriverTest.java`, same two flags, same classpath. `Runner` finds `public static void test*(TestStore)` reflectively,
builds and closes a fresh store per method, prints a transcript and exits non-zero if anything failed. A `test*` method
that is not `public static` is *skipped with a warning*, not run, and a class with no test methods throws rather than
reporting a green zero.

The suite's own harness tests (`toolkit/src/test/java/toolkit/harness/`) are worked examples of the same calls, but not
copy-paste templates: they are JUnit-registered and take their `TestStore` from the test-scoped `TestStoreExtension`
rather than from `Runner`. The kit underneath is the same one.

## Instruments

Nothing in [`tools/`](tools/README.md) ships, and `mvn verify` runs none of it. Two you will want:

- **`tools/inspect/nx`** — one entry point, one subcommand each: `db` (store browser), `bind` (the silent-null sweep —
  values that failed to bind and left a field empty without raising anything), `geo`, `lens`, `xsd` (XSD-validate an
  export zip), `prov` (read the sidecar).
- **`tools/diff`** — the before/after loop for "did my change alter the data?". `diff/Fragments.java` is the default
  store check (order-independent digest, so an insert-order change does not trip it); `diff/export_zip.py` is the
  default export check.

Each tool's own header records what that instrument **cannot** see as carefully as what it can;
`diff/StoreNames.java` is the model.

# Build

Requires a JDK 25 and Maven.

```
mvn -B -ntp verify
```

Two artifacts come out of `package`, and they are the same code in two shapes:

| artefact                      | what it is                                                                                                                      |
|-------------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `netex-toolkit.jar` + `deps/` | the thin jar and its dependencies as loose files. What `tools/gate/compile.sh` and the source-launched drivers run on           |
| `netex-toolkit-shaded.jar`    | the same classes with every dependency bundled in. One file, no `deps/` to keep in step. What a downstream consumer should take |

# Design notes

## XML processing quirks

The toolkit has a custom netex-model due to how NeTEx meets JAXB. The ones worth knowing before you trust a value:

- **Enum whitespace.** Every NeTEx enumeration restricts `xs:NMTOKEN`, whose whitespace facet is *collapse*, so
  `<TransportMode>bus </TransportMode>` and `bus` are the same value. XJC binds such a type with the default base
  `String`, and the RI looks the raw lexical up in a map keyed on exact text: a padded value misses and the RI returns
  **null, silently** — no exception, no `ValidationEvent`. The element then ceases to exist on the bean and in the next
  marshal. `EnumWhitespace` collapses the text before the RI sees it.
- **Date and time are carriers, not values.** `xs:time`/`xs:date`/`xs:dateTime`/`xs:duration` bind to lexical-preserving
  carriers, because the stock bindings drop the timezone offset, silently fold `24:00:00` to `00:00:00`, and reject the
  year/month duration form.
- **`xs:normalizedString`.** Jakarta's adapter applies the whitespace facet and rewrites CR/LF/TAB to spaces, destroying
  the line breaks feeds put in addresses and descriptions. `VerbatimStringAdapter` replaces it and does nothing at all,
  deliberately.
- **Child-element order.** The store keeps the source's order and the exporter copies the bytes, so a schema-invalid
  source feed produces an export that fails `cvc-complex-type.2.4.a`. `FragmentOrder` re-ranks top-level children into
  the declared XSD sequence by moving spans.
- **Marshal-time omit-default.** An `@XmlList` field emits `<DaysOfWeek></DaysOfWeek>` when its list is non-null and
  empty, and xjc bakes XSD defaults into *getters* so a field acquires the value the moment anything copies
  getter→field. Both are normalised at the single chokepoint every mutated object passes through, by rules derived from
  a virgin instance rather than written down, so regenerating from a newer schema cannot leave a stale table behind. One
  case is uncoverable: `@XmlElement(defaultValue=…)` on a *list* field, where the divergence is created at unmarshal and
  an empty source is afterwards indistinguishable from one that spelled the default out.
- **2.0 redefines `MultilingualString` as a mixed type**, so `getValue()`/`setValue()` cease to exist and there is one
  state for null and empty — `<Name/>` reads null where simple content read `""`. Every call site goes through the `Mls`
  accessor seam.
- **Two StAX implementations, chosen once** (`Stax`). Woodstox where per-event character offsets are required, because
  that is what lets `RefPatcher` splice attributes into stored bytes; Aalto for decode, because it is faster.
- **An element name does not determine a class.** Some NeTEx names are declared with different types in different
  parents, so `RefElementIndex` returns a candidate set; a caller must reach the same verdict for all of them or fall
  back to unmarshalling.
- **A NeTEx `ref` attribute is unqualified**, so a namespace-blind attribute read will happily match `gml:id` where `id`
  was meant.
- **`BindEvents` counts what failed to bind** — read its note before trusting a zero: it cannot see a value whose
  *adapter* returned an object, which is exactly what the date/time carriers do.
- **One JAXB `Coordinator` sentinel is pinned per thread**, because the RI otherwise allocates a `ThreadLocalMap.Entry`
  on every single XML event. No output byte differs either way; worth knowing if you embed the library in your own
  threads.

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

- **Edges live inside LMDB**: the stage artifact stays one directory a caller can move atomically, edge persistence
  commits in the same transaction as the objects.
- **Deletes tombstone in place** rather than rewriting the index, so a delete costs one block, and a kind is compacted
  only once its dead slots pass a fraction of its total. Deletion is indexed by referrer, not scanned.
- **Key layout is one class**, `toolkit.keycodec`: `id NUL version NUL [class_idx LE16]` for the id index, and
  `(class_idx, local_key)` packed into the 8-byte `full_key` everything else passes around.
- **The class table is written once, at creation.** A store built against a different model generation therefore cannot
  be a clone source for a chain.
- **The default map size is 64 GiB** and the drivers are run at `-Xmx4g`–`6g`.

**Two sidecar files sit beside the store**, answering two different questions and deliberately not merged.
`.provenance.json` is a property of the **code**: which behaviour-changing fixes the writing jar had. It intersects
across a merge — a merged store is only as capable as its least capable input — and a downstream stage can refuse an
input on it. `.loadreport.json` is a property of a **run over an input**: what it tolerated. Tolerating something is
only defensible if the toleration is legible afterwards.

# Licensing

**This repository is AGPL-3.0** — [`LICENSE`](LICENSE) carries the full, verbatim FSF text.
