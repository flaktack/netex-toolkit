package toolkit.test;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.Marshaller;
import noi.netex.model.EntityStructure;
import noi.netex.model.GeneralFrame;
import noi.netex.model.General_VersionFrameStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.PublicationDeliveryStructure;
import noi.netex.time.XmlDateTime;
import toolkit.codec.FragmentCodec;
import toolkit.harness.DbToDb;
import toolkit.load.NetexLoader;
import toolkit.model.ModelRegistry;
import toolkit.store.ObjectRow;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;

import javax.xml.namespace.QName;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// A per-test writable store in a temporary directory, populated from a NeTEx fragment string
/// through the real loader.
///
/// Fixture strings are plain NeTEx; the wrapper is a fixed skeleton.
public final class TestStore implements AutoCloseable {

    /// The NeTEx document skeleton every fixture string is wrapped in.
    public static final String SKELETON =
            "<PublicationDelivery xmlns=\"http://www.netex.org.uk/netex\""
                    + " xmlns:gml=\"http://www.opengis.net/gml/3.2\" version=\"ntx:1.1\">"
                    + "<PublicationTimestamp>2026-01-01T00:00:00</PublicationTimestamp>"
                    + "<ParticipantRef>test</ParticipantRef>"
                    + "<dataObjects><CompositeFrame id=\"cf\" version=\"1\">"
                    + "<frames>__FRAMES__</frames>"
                    + "</CompositeFrame></dataObjects></PublicationDelivery>";

    private static final ObjectFactory FACTORY = new ObjectFactory();
    private static NetexLoader loader; // ~2.7 s JAXB init: shared across all TestStores of a JVM

    public final Store store;
    public final String format;
    private final Path dir;
    private final List<TestStore> children = new ArrayList<>();
    private int nameCounter;

    private TestStore(Store store, String format, Path dir) {
        this.store = store;
        this.format = format;
        this.dir = dir;
    }

    /// A fresh, writable, empty store in a new temporary directory.
    public static TestStore create(String format) throws Exception {
        Path dir = Files.createTempDirectory("toolkit-test-");
        try {
            return new TestStore(Stores.create(dir.resolve("test.lmdb"), format), format, dir);
        } catch (Throwable t) {
            wipe(dir);
            throw t;
        }
    }

    static synchronized NetexLoader sharedLoader() {
        if (loader == null) loader = new NetexLoader();
        return loader;
    }

    /// Parse `framesXml` — the inner content of `<frames>` — into this store through the real
    /// loader, then resolve + resolveEmbeddings.
    public long loadNetex(String framesXml) throws Exception {
        long n = loadInto(store, framesXml);
        store.resolve();
        store.resolveEmbeddings();
        return n;
    }

    private static long loadInto(Store target, String framesXml) throws Exception {
        String xml = SKELETON.replace("__FRAMES__", framesXml);
        return sharedLoader().loadStream(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), target, "test").objects();
    }

    /// A path inside this TestStore's temp dir (for path-taking drivers' intermediate outputs).
    public Path tempPath(String name) {
        return dir.resolve(name);
    }

    /// A populated, closed store file for drivers that open their inputs themselves. Same format
    /// as this store.
    public Path buildDbFile(String framesXml, String name) throws Exception {
        Path path = dir.resolve(name);
        try (Store db = Stores.create(path, format)) {
            loadInto(db, framesXml);
            db.resolve();
            db.resolveEmbeddings();
        }
        return path;
    }

    /// Run a 4-arg transform from this store into a fresh same-format target; the target is closed
    /// with this TestStore.
    public TestStore runDbToDb(DbToDb.ApplyFn transform) throws Exception {
        // The child owns its own subdirectory: closing it must never delete the parent's live db.
        Path childDir = Files.createDirectories(dir.resolve("target-" + (nameCounter++)));
        TestStore target = new TestStore(Stores.create(childDir.resolve("target.lmdb"), format), format, childDir);
        children.add(target);
        try (Txn stx = store.roTxn(); Txn dtx = target.store.rwTxn()) {
            transform.apply(store, stx, target.store, dtx);
            dtx.commit();
        }
        target.store.resolveEmbeddings();
        return target;
    }

    /// The whole database as one GeneralFrame PublicationDelivery, members in dependency order —
    /// referenced objects before their referrers. Within a rank, sorted by (class, id, version),
    /// so the output is deterministic.
    public String exportNetex() throws Exception {
        List<Object> ordered = new ArrayList<>();
        try (Txn tx = store.roTxn()) {
            Map<Long, Object> objs = new LinkedHashMap<>();
            for (Class<?> clazz : store.dbNames(tx)) {
                for (ObjectRow r : store.iterObjects(tx, clazz)) {
                    objs.put(store.fullKeyOf(clazz, r.localKey()), r.object());
                }
            }
            Map<Long, List<Long>> dependents = new HashMap<>(); // target -> referrers waiting on it
            Map<Long, Integer> pending = new HashMap<>();       // referrer -> unemitted target count
            for (long[] e : store.iterEdges(tx)) {
                if (!objs.containsKey(e[0]) || !objs.containsKey(e[1]) || e[0] == e[1]) continue;
                dependents.computeIfAbsent(e[1], k -> new ArrayList<>()).add(e[0]);
                pending.merge(e[0], 1, Integer::sum);
            }
            Comparator<Long> byLabel = Comparator.comparing(fk -> {
                Object o = objs.get(fk);
                EntityStructure es = (EntityStructure) o;
                return o.getClass().getSimpleName() + "|" + es.getId();
            });
            TreeMap<Long, Object> ready = new TreeMap<>(byLabel.thenComparing(fk -> fk));
            for (Map.Entry<Long, Object> en : objs.entrySet()) {
                if (!pending.containsKey(en.getKey())) ready.put(en.getKey(), en.getValue());
            }
            while (!ready.isEmpty()) {
                Map.Entry<Long, Object> next = ready.pollFirstEntry();
                ordered.add(next.getValue());
                for (Long referrer : dependents.getOrDefault(next.getKey(), List.of())) {
                    if (pending.merge(referrer, -1, Integer::sum) == 0) {
                        pending.remove(referrer);
                        ready.put(referrer, objs.get(referrer));
                    }
                }
            }
            if (ordered.size() != objs.size()) { // reference cycle: append the rest deterministically
                objs.keySet().stream().filter(pending::containsKey).sorted(byLabel)
                        .forEach(fk -> ordered.add(objs.get(fk)));
            }
        }

        General_VersionFrameStructure.Members members = new General_VersionFrameStructure.Members();
        List<JAXBElement<? extends EntityStructure>> list =
                members.getGeneralFrameMemberOrDataManagedObjectOrEntity_Entity();
        for (Object o : ordered) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            JAXBElement<? extends EntityStructure> el = new JAXBElement(
                    new QName(ModelRegistry.NETEX_NS, o.getClass().getSimpleName()), o.getClass(), o);
            list.add(el);
        }
        GeneralFrame gf = new GeneralFrame();
        gf.setId("Database");
        gf.setVersion("1");
        gf.setMembers(members);
        PublicationDeliveryStructure pd = new PublicationDeliveryStructure()
                .withVersion("ntx:1.1")
                // Synthesized, so the carrier holds no lexical and re-emits canonically.
                .withPublicationTimestamp(XmlDateTime.from(LocalDateTime.of(2026, 1, 1, 0, 0, 0)))
                .withParticipantRef("PyNeTExConv")
                .withDataObjects(new PublicationDeliveryStructure.DataObjects()
                        .withCompositeFrameOrCommonFrame(FACTORY.createGeneralFrame(gf)));
        Marshaller m = FragmentCodec.packageContext().createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);
        m.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE); // no <?xml?> declaration
        // Same omission rule as FragmentCodec, or the two disagree on what a default omits.
        m.setListener(toolkit.codec.SchemaDefaults.LISTENER);
        StringWriter sw = new StringWriter();
        m.marshal(FACTORY.createPublicationDelivery(pd), sw);
        return sw.toString();
    }

    @Override
    public void close() {
        try {
            for (TestStore child : children) {
                try {
                    child.close();
                } catch (Exception ignored) {
                }
            }
        } finally {
            try {
                store.close();
            } catch (Exception ignored) {
            }
            wipe(dir);
        }
    }

    private static void wipe(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
