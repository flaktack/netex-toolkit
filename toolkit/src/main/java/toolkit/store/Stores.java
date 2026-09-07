package toolkit.store;

import org.lmdbjava.ByteArrayProxy;
import org.lmdbjava.Dbi;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;
import toolkit.store.v2.V2Store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// Store factory: format sniffing ("which store wrote this directory?") and creation.
///
/// There is one store format. `v1` is retired; its identifier survives as [#RETIRED_V1] so a store
/// or command line naming it fails with that sentence rather than "unknown store format".
public final class Stores {

    public static final String V2 = V2Store.FORMAT_ID;

    /// The retired format id. Never creatable; recognised only to be refused.
    public static final String RETIRED_V1 = "v1";

    /// The embedding-index scope of the NeTEx profile this repository loads: embeddable classes
    /// that are realistic reference targets. Any caller can pass another scope, and the store then
    /// indexes and marks under that one.
    ///
    /// Matched against the JAXB simple name of the object the parser built, which is not always the
    /// NeTEx element name — XJC generates no class for a global element declared with a named type,
    /// and many NeTEx elements are. Two entries are worth spelling out:
    ///
    /// - `RouteView` never matches. The element binds to a class extending `DerivedViewStructure`
    ///    — a view, not an `EntityStructure`, with no `getId()` and not registrable. Kept, pinned
    ///    by `TestEmbeddingScope`, so a model regeneration making it an entity is not a silent gap.
    /// - `Entrance` needs `SiteEntrance_VersionStructure` listed too
    ///    (PROV-CAP: embed-scope-siteentrance): the schema declares `<Entrance>` with that named
    ///    type, so the parser builds a structure that is registrable and id-carrying but simply
    ///    named something else. Without the alias the index misses every id-bearing `<Entrance>`.
    ///    The bare `Entrance` entry never matches anything.
    ///
    /// Changing this set changes stored bytes: it is half of the scope tag every store records, so
    /// a widened list makes every existing index degrade to a corpus scan until the store is
    /// rebuilt.
    public static final AbstractLmdbStore.EmbeddingScope NETEX_EMBEDDING_SCOPE =
            new AbstractLmdbStore.EmbeddingScope(java.util.Set.of(
                    "Quay", "StopPointInJourneyPattern", "Level",
                    "Entrance", "SiteEntrance_VersionStructure",
                    "PointOnRoute", "BoardingPosition", "FlexibleArea", "HailAndRideArea"));

    /// The retired format's `_class_idx[FF FF]` key-format marker values.
    static final String RETIRED_V1_MARKER = "raw-utf8-nul:";

    private static final String RETIRED =
            "the v1 store format was retired; v2 is the only format. A v1 database "
            + "cannot be read by this jar — rebuild it from its feed, or read it with a "
            + "pre-retirement jar and export.";

    private Stores() {}

    /// Open an existing db, or — rw only — create a fresh one in `defaultFormat`.
    public static Store open(Path path, boolean readonly, String defaultFormat) {
        return open(path, readonly, defaultFormat, NETEX_EMBEDDING_SCOPE);
    }

    /// As [boolean, String)][#open(Path,], under an explicit embedding-index scope.
    ///
    /// Opening an existing store under a scope other than the one it was built with is legal and
    /// lossless: the store drops its index-completeness marker and the next `resolveEmbeddings`
    /// takes the corpus scan.
    public static Store open(Path path, boolean readonly, String defaultFormat,
                             AbstractLmdbStore.EmbeddingScope embScope) {
        if (Files.exists(path.resolve("data.mdb"))) {
            sniffFormat(path);
            Store s = new V2Store(path, readonly, embScope);
            // A pre-existing db opened rw: its bytes came from some other jar, so the effective
            // capability set is the intersection, or unknown with no sidecar to intersect with.
            if (!readonly) toolkit.prov.Provenance.stampOpen(path, false);
            return s;
        }
        if (readonly) throw new IllegalArgumentException("cannot open storage readonly: " + path + " does not exist");
        return create(path, defaultFormat, embScope);
    }

    public static Store open(Path path, boolean readonly) {
        return open(path, readonly, V2);
    }

    /// True when `path` holds an existing store (its LMDB data file is present).
    public static boolean exists(java.nio.file.Path path) {
        return java.nio.file.Files.exists(path.resolve("data.mdb"));
    }

    /// Recursively delete a store directory; a missing path is a no-op.
    public static void destroy(java.nio.file.Path path) throws java.io.IOException {
        if (!java.nio.file.Files.exists(path)) return;
        try (var walk = java.nio.file.Files.walk(path)) {
            for (var p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.delete(p);
            }
        }
    }

    /// Create (or open rw) a store. [#V2] is the only accepted format.
    public static Store create(Path path, String format) {
        return create(path, format, NETEX_EMBEDDING_SCOPE);
    }

    /// As [String)][#create(Path,], under an explicit embedding-index scope.
    public static Store create(Path path, String format, AbstractLmdbStore.EmbeddingScope embScope) {
        boolean fresh = !Files.exists(path.resolve("data.mdb"));
        Store s = switch (format) {
            case V2 -> new V2Store(path, false, embScope);
            case RETIRED_V1 -> throw new IllegalArgumentException("--format v1: " + RETIRED);
            default -> throw new IllegalArgumentException("unknown store format: " + format);
        };
        // A db created from nothing carries exactly the running jar's capability set.
        toolkit.prov.Provenance.stampOpen(path, fresh);
        return s;
    }

    /// [#V2] for an existing db directory, or a refusal.
    ///
    /// No fallback: a store that does not say `java-v2:1` is not readable here, and the caller is
    /// told which reason applies — a retired v1 database, or bytes that are not a toolkit store.
    public static String sniffFormat(Path path) {
        try (Env<byte[]> env = Env.create(ByteArrayProxy.PROXY_BA)
                .setMapSize(64L << 30)
                .setMaxDbs(4)
                .open(path.toFile(), EnvFlags.MDB_RDONLY_ENV, EnvFlags.MDB_NOTLS)) {
            String found = null;
            try {
                Dbi<byte[]> meta = env.openDbi("_meta".getBytes(StandardCharsets.UTF_8));
                try (org.lmdbjava.Txn<byte[]> tx = env.txnRead()) {
                    byte[] f = meta.get(tx, "format".getBytes(StandardCharsets.UTF_8));
                    if (f != null) {
                        found = new String(f, StandardCharsets.UTF_8);
                        if (V2Store.FORMAT.equals(found)) return V2;
                    }
                }
            } catch (RuntimeException e) {
                // no _meta map at all — v1's metadata lived in _class_idx, so fall through
            }
            if (looksLikeRetiredV1(env)) {
                throw new IllegalStateException(path + ": v1 (" + RETIRED_V1_MARKER + "*) — " + RETIRED);
            }
            throw new IllegalStateException(path + ": not a toolkit store — _meta/format is "
                    + (found == null ? "absent" : "\"" + found + "\"") + ", expected \"" + V2Store.FORMAT + "\"");
        }
    }

    /// The v1 key-format guard row: `_class_idx[FF FF] = "raw-utf8-nul:1"` (or `:2`).
    private static boolean looksLikeRetiredV1(Env<byte[]> env) {
        try {
            Dbi<byte[]> classIdx = env.openDbi("_class_idx".getBytes(StandardCharsets.UTF_8));
            try (org.lmdbjava.Txn<byte[]> tx = env.txnRead()) {
                byte[] m = classIdx.get(tx, new byte[] {(byte) 0xFF, (byte) 0xFF});
                return m != null && new String(m, StandardCharsets.UTF_8).startsWith(RETIRED_V1_MARKER);
            }
        } catch (RuntimeException e) {
            return false;
        }
    }
}
