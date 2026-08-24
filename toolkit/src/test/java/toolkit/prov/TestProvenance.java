package toolkit.prov;

// The store staleness gate.
//
// Two halves, and the second is the important one:
//
//   BEHAVIOUR   the sidecar is written on a rw open, `cloneFrom` takes the MINIMUM of the source
//               and target capability sets, absence means UNKNOWN/refuse, and `check` fires on a
//               PROPER SUBSET, not only on a missing file.
//
//   HONESTY     the capability vector is pinned to the tree from both ends. A capability with no
//               code behind it fails, and a PROV-CAP marker naming an unknown capability fails, so
//               a NEW FIX CANNOT LAND WITHOUT BEING CLASSIFIED.
//
// NEUTER PROOF: run with -Dtoolkit.prov=off and exactly the four tests that depend on the
// production hooks fail — testFreshStoreIsStamped, testSidecarIsOutsideLmdb,
// testCloneTakesTheMinimum, testCloneFromUnstampedSourceIsUnknown. The marker-discipline tests keep
// passing, because the vector is a source-tree property and must be pinned whether or not stamping
// is enabled; `attest` also keeps working, being an explicit operator command.

import org.junit.jupiter.api.Test;
import toolkit.Sources;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestProvenance {

    // ================================================================ behaviour

    private static Path tmp() throws IOException {
        return Files.createTempDirectory("prov");
    }

    /// A store this jar created from nothing carries the running set, and it is a measurement.
    @Test
    void testFreshStoreIsStamped() throws Exception {
        Path d = tmp().resolve("fresh.lmdb");
        try (Store s = Stores.create(d, Stores.V2)) {
            Check.that(s != null, "store created");
        }
        Provenance.Prov p = Provenance.read(d);
        Check.that(p != null, "a fresh store has a sidecar");
        Check.that(p.capsKnown(), "a fresh store's capability set is KNOWN");
        Check.equals(new TreeSet<>(Capabilities.current()), new TreeSet<>(p.capsEffective()),
                "a fresh store carries the running jar's whole capability vector");
        Check.that(Provenance.check(d).ok(), "a fresh store passes the gate");
    }

    /// Absence of a sidecar is UNKNOWN, and UNKNOWN is refused — never "fine".
    @Test
    void testMissingSidecarIsRefused() throws Exception {
        Path d = tmp().resolve("bare.lmdb");
        Files.createDirectories(d);
        Provenance.Verdict v = Provenance.check(d);
        Check.that(!v.present(), "no sidecar");
        Check.that(!v.ok(), "a store with no sidecar is REFUSED");
        Check.equals(new TreeSet<>(Capabilities.current()), new TreeSet<>(v.missing()),
                "with nothing known, everything is reported missing");
    }

    /// THE proper-subset path, separate from the missing-file path: a store that HAS a sidecar but
    /// lacks one capability is still refused, and the report names exactly the one it lacks.
    @Test
    void testProperSubsetIsRefused() throws Exception {
        Path d = tmp().resolve("reduced.lmdb");
        Files.createDirectories(d);
        Set<String> reduced = new LinkedHashSet<>(Capabilities.current());
        reduced.remove("enum-collapse");
        Provenance.attest(d, reduced, "unit test: deliberately reduced vector");
        Provenance.Verdict v = Provenance.check(d);
        Check.that(v.present(), "sidecar present");
        Check.that(v.capsKnown(), "and its set is known");
        Check.that(!v.ok(), "a PROPER SUBSET of the running set is REFUSED");
        Check.equals(Set.of("enum-collapse"), new TreeSet<>(v.missing()),
                "the report names exactly the missing capability");
        Check.that(v.attested(), "an operator assertion is flagged as such, never as a measurement");
    }

    /// An equal set passes; attesting can only ever lower a set, never raise one.
    @Test
    void testAttestCannotRaiseASet() throws Exception {
        Path d = tmp().resolve("attest.lmdb");
        Files.createDirectories(d);
        Set<String> reduced = new LinkedHashSet<>(Capabilities.current());
        reduced.remove("denorm-depth");
        Provenance.attest(d, reduced, "unit test: first, narrow");
        Provenance.attest(d, Capabilities.current(), "unit test: then try to widen");
        Provenance.Verdict v = Provenance.check(d);
        Check.equals(Set.of("denorm-depth"), new TreeSet<>(v.missing()),
                "a later, wider attestation cannot launder a store the machinery knows is narrow");
    }

    /// attest refuses a blank reason and an unknown capability name.
    @Test
    void testAttestIsFussy() throws Exception {
        Path d = tmp().resolve("fussy.lmdb");
        Files.createDirectories(d);
        boolean threwOnBlank = false;
        try {
            Provenance.attest(d, Capabilities.current(), "  ");
        } catch (IllegalArgumentException e) {
            threwOnBlank = true;
        }
        Check.that(threwOnBlank, "attest without a written reason is refused");
        boolean threwOnTypo = false;
        try {
            Provenance.attest(d, Set.of("denorm-dpeth"), "typo");
        } catch (IllegalArgumentException e) {
            threwOnTypo = true;
        }
        Check.that(threwOnTypo, "attest with an unknown capability name is refused");
    }

    /// cloneFrom copies stored fragments verbatim; the target must come out as the
    /// MINIMUM of the two sets, exactly as emb_idx_done is retracted when a source lacks it.
    @Test
    void testCloneTakesTheMinimum() throws Exception {
        Path root = tmp();
        Path src = root.resolve("src.lmdb");
        Path dst = root.resolve("dst.lmdb");
        try (Store s = Stores.create(src, Stores.V2)) {
            Check.that(s != null, "source created");
        }
        // Retro-fit the source as if it had been written by a jar without enum-collapse.
        Set<String> stale = new LinkedHashSet<>(Capabilities.current());
        stale.remove("enum-collapse");
        Provenance.attest(src, stale, "unit test: simulate a store built without enum-collapse");

        try (Store s = Stores.open(src, true);
             Store t = Stores.create(dst, Stores.V2);
             Txn stx = s.roTxn();
             Txn ttx = t.rwTxn()) {
            t.cloneFrom(s, stx, ttx, null, 0);
            ttx.commit();
        }
        Provenance.Verdict v = Provenance.check(dst);
        Check.that(!v.ok(), "a target that cloned a stale source is stale");
        Check.equals(Set.of("enum-collapse"), new TreeSet<>(v.missing()),
                "MINIMUM, not union: the target lost exactly what the source lacked");
    }

    /// A clone source with no sidecar makes the target UNKNOWN, not merely narrower.
    @Test
    void testCloneFromUnstampedSourceIsUnknown() throws Exception {
        Path root = tmp();
        Path src = root.resolve("src.lmdb");
        Path dst = root.resolve("dst.lmdb");
        try (Store s = Stores.create(src, Stores.V2)) {
            Check.that(s != null, "source created");
        }
        Files.delete(src.resolve(Provenance.FILE));   // as every pre-tool store in the tree is
        try (Store s = Stores.open(src, true);
             Store t = Stores.create(dst, Stores.V2);
             Txn stx = s.roTxn();
             Txn ttx = t.rwTxn()) {
            t.cloneFrom(s, stx, ttx, null, 0);
            ttx.commit();
        }
        Provenance.Verdict v = Provenance.check(dst);
        Check.that(v.present(), "the target still has a sidecar");
        Check.that(!v.capsKnown(), "but its capability set is UNKNOWN, inherited from the source");
        Check.that(!v.ok(), "and UNKNOWN is refused");
    }

    /// The sidecar lives OUTSIDE LMDB — no byte-identity gate may see it.
    @Test
    void testSidecarIsOutsideLmdb() throws Exception {
        Path d = tmp().resolve("outside.lmdb");
        try (Store s = Stores.create(d, Stores.V2)) {
            Check.that(s != null, "store created");
        }
        Check.that(Files.isRegularFile(d.resolve(".provenance.json")),
                "the sidecar is a plain file in the store directory");
        Check.that(Files.isRegularFile(d.resolve("data.mdb")), "data.mdb is where it always was");
        long before = Files.size(d.resolve("data.mdb"));
        Provenance.attest(d, Capabilities.current(), "unit test: stamping must not touch LMDB");
        Check.equals(before, Files.size(d.resolve("data.mdb")),
                "stamping does not move a byte of data.mdb");
    }

    // ================================================================ honesty (the marker discipline)

    /// toolkit/prov is the mechanism itself; its own prose quotes the marker syntax.
    private static boolean isMechanism(Path p) {
        String s = p.toString();
        return s.contains("/toolkit/prov/") || s.endsWith("prov/TestProvenance.java");
    }

    /// The scan's subject. The locator, the root list and the operative-first guard live in
    /// [Sources], shared with TestConf's honesty scan of the same tree.
    private static List<Path> sources() throws IOException {
        return Sources.all(TestProvenance::isMechanism);
    }

    /// The dot is admitted because a schema version is the natural way to name a model capability:
    /// without it `netex-2.0-model` matches as `netex-2`, a name no capability declares, and both
    /// honesty tests fail. A marker still has to name a declared capability exactly.
    private static final Pattern CAP_MARKER = Pattern.compile("PROV-CAP:\\s*([a-z0-9][a-z0-9.-]*)");

    /// Capabilities whose fix site is DOWNSTREAM, named rather than pattern-matched.
    ///
    /// Both are fixes the corridor pipeline performs, and both left with it for `../noi-pipeline`.
    /// The DECLARATIONS stay here because this is the jar that writes the provenance sidecar:
    /// delete them and the stamp silently stops making the claim.
    ///
    /// [#testDownstreamCapabilitiesAreStillDeclared] keeps this list from rotting.
    private static final java.util.Set<String> FIXED_DOWNSTREAM =
            java.util.Set.of("hour24-semantics", "dedup-definition-days");

    /// Every capability in the vector has at least one fix site carrying its marker.
    @Test
    void testEveryCapabilityHasAFixSite() throws Exception {
        TreeMap<String, List<String>> sites = new TreeMap<>();
        for (Path p : sources()) {
            Matcher m = CAP_MARKER.matcher(Sources.read(p));
            while (m.find()) sites.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(p.getFileName().toString());
        }
        List<String> orphans = new ArrayList<>();
        for (String c : Capabilities.declared()) {
            if (FIXED_DOWNSTREAM.contains(c)) continue;
            if (!sites.containsKey(c)) orphans.add(c);
        }
        Check.equals(List.of(), orphans,
                "every declared capability names real code (found: " + sites + ")");
    }

    /// The exemption list cannot outlive what it exempts: a name here that no longer names a
    /// declared capability would wave through the next orphan that shares its spelling.
    ///
    /// It does NOT check the downstream fix site exists — this repository cannot see that tree.
    @Test
    void testDownstreamCapabilitiesAreStillDeclared() {
        TreeSet<String> stale = new TreeSet<>(FIXED_DOWNSTREAM);
        stale.removeAll(Capabilities.declared());
        Check.equals(new TreeSet<String>(), stale,
                "every downstream-exempt name is still a declared capability");
    }

    /// Every marker in the tree names a declared capability — no typos, no dead names.
    @Test
    void testEveryMarkerNamesAKnownCapability() throws Exception {
        TreeSet<String> unknown = new TreeSet<>();
        for (Path p : sources()) {
            Matcher m = CAP_MARKER.matcher(Sources.read(p));
            while (m.find()) if (!Capabilities.declared().contains(m.group(1))) unknown.add(m.group(1) + " @ " + p);
        }
        Check.equals(new TreeSet<String>(), unknown, "no PROV-CAP marker names an unknown capability");
    }
}
