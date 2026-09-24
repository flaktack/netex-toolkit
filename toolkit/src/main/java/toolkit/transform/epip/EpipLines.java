package toolkit.transform.epip;

// The EPIP line generator.
//
// additionalOperators.transportOrganisationRef is the one choice-group site: a
// List<JAXBElement<? extends TransportOrganisationRefStructure>> substitution-group list. The
// demotion wraps the ref object in its typed ObjectFactory element (createOperatorRef /
// createAuthorityRef) so the serialized element names are right; both OperatorRefStructure and
// AuthorityRef extend the list's bound.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.EntityStructure;
import noi.netex.model.FlexibleLine;
import noi.netex.model.Line;
import noi.netex.model.Line_VersionStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.TransportOrganisationRefStructure;
import noi.netex.model.TransportOrganisationRefs_RelStructure;
import noi.netex.model.TypeOfValue_VersionStructure;
import noi.netex.model.ValueSet;
import noi.netex.model.VersionOfObjectRefStructure;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

public final class EpipLines {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    private EpipLines() {}

    /// The TypeOfValue classes a Line may name, and whose definitions decide whether its ref
    /// survives. Both are listed as Line fields by the profile, so the data is permitted; a
    /// reference is only allowed to stay when the document will carry what it names.
    private static final java.util.List<String> LINE_TYPE_CLASSES =
            java.util.List.of("TypeOfService", "TypeOfProductCategory");

    /// Line type refs nulled because nothing defines them, reported at end of pass.
    private static final LongAdder UNRESOLVED_TYPE_REF = new LongAdder();

    /// Re-emit every Line with branding dropped and each type ref kept only where it resolves; when
    /// a Line carries both an operatorRef and an authorityRef, one of them moves to the front of
    /// additionalOperators (creating the list when there is none) and is cleared — which one is
    /// moved is decided by [EpipConfig#AUTHORITY_REFERENCE]: `true` keeps the Authority primary and
    /// demotes the Operator; `false` the reverse.
    ///
    /// Yield order: each Line in class-map order, processed lazily as it is pulled. The switch is
    /// read statically.
    public static Iterator<Object> epipLineGenerator(
            Store dbRead, Txn txnRead, GeneratorDefaults generatorDefaults) {
        // Query phase: the ids a type ref may resolve against. A publisher that references a code
        // list it never publishes is common -- the reference is then not data the profile permits
        // but a dangling one it forbids, so the field goes and the count says how often.
        //
        // A definition counts wherever the document will carry it, and a code list is usually
        // published as a ValueSet holding its members, so standalone rows of the member class are
        // empty while every id a Line names is present. Reading only the rows drops every such
        // reference.
        Set<String> definedTypes = new HashSet<>();
        for (String n : LINE_TYPE_CLASSES) {
            Class<?> c = dbRead.classForName(n);
            if (c == null) continue;
            for (Object o : dbRead.iterOnlyObjects(txnRead, c)) {
                String id = ((EntityStructure) o).getId();
                if (id != null) definedTypes.add(id);
            }
        }
        if (dbRead.classForName("ValueSet") != null) {
            for (Object o : dbRead.iterOnlyObjects(txnRead, ValueSet.class)) {
                ValueSet vs = (ValueSet) o;
                if (vs.getValues() == null) continue;
                for (JAXBElement<? extends TypeOfValue_VersionStructure> el
                        : vs.getValues().getTypeOfValue()) {
                    TypeOfValue_VersionStructure v = el.getValue();
                    if (v != null && v.getId() != null) definedTypes.add(v.getId());
                }
            }
        }
        // Both concrete line classes. A FlexibleLine is a Line with flexible-service fields
        // added, so it carries the same organisation CHOICE and the same refs the profile has no
        // field for; draining one class map alone leaves every FlexibleLine unprocessed.
        //
        // Drained in turn rather than collected: the generator's contract is that it decodes one
        // object at a time, and a store holds every Line it has.
        Iterator<Object> plain = dbRead.iterOnlyObjects(txnRead, Line.class).iterator();
        Iterator<Object> flexible = dbRead.iterOnlyObjects(txnRead, FlexibleLine.class).iterator();
        return new Iterator<>() {
            private boolean reported;

            @Override
            public boolean hasNext() {
                boolean more = plain.hasNext() || flexible.hasNext();
                if (!more && !reported) {
                    reported = true;
                    reportUnresolvedTypeRefs();
                }
                return more;
            }

            @Override
            public Object next() {
                Line_VersionStructure line =
                        (Line_VersionStructure) (plain.hasNext() ? plain.next() : flexible.next());
                process(line, definedTypes);
                return line;
            }
        };
    }

    private static void process(Line_VersionStructure line, Set<String> definedTypes) {
        // BrandingRef is not a Line field in this profile, so it goes whatever it names.
        line.setBrandingRef(null);
        // These two are. They stay when the document will define what they name, and go when it
        // will not: a reference has to resolve inside the document it is published in.
        if (!resolves(line.getTypeOfServiceRef(), definedTypes)) line.setTypeOfServiceRef(null);
        if (!resolves(line.getTypeOfProductCategoryRef(), definedTypes)) {
            line.setTypeOfProductCategoryRef(null);
        }
        if (line.getOperatorRef() != null && line.getAuthorityRef() != null) {
            if (EpipConfig.AUTHORITY_REFERENCE) {
                demoteAdditionalOperator(line, FACTORY.createOperatorRef(line.getOperatorRef()));
                line.setOperatorRef(null);
            } else {
                demoteAdditionalOperator(line, FACTORY.createAuthorityRef(line.getAuthorityRef()));
                line.setAuthorityRef(null);
            }
        }
    }

    /// Put the demoted reference first when additionalOperators exists and its list is non-empty,
    /// else replace the container with a fresh one holding just this ref.
    ///
    /// The list it joins may already hold references the feed put there — a line's co-operating
    /// companies, which some profiles populate — and nothing in the element distinguishes those
    /// from the one demoted here. Position is the only marker there is, so a consumer restoring
    /// the primary reference reads the first entry of its kind.
    /// Whether a ref is absent (nothing to keep) or names a definition the store has. A ref that
    /// names nothing is counted here, since that is the case a reader would want to know about.
    private static boolean resolves(VersionOfObjectRefStructure ref, Set<String> definedTypes) {
        if (ref == null || ref.getRef() == null) return false;
        if (definedTypes.contains(ref.getRef())) return true;
        UNRESOLVED_TYPE_REF.increment();
        return false;
    }

    /// Report the nulled-because-undefined type refs and reset. Called by the generator at the end
    /// of its own pass.
    private static void reportUnresolvedTypeRefs() {
        long n = UNRESOLVED_TYPE_REF.sumThenReset();
        if (n > 0) {
            Log.info("[epip] %,d Line type refs were dropped for naming a TypeOfService or "
                    + "TypeOfProductCategory the feed never defines", n);
        }
    }

    private static void demoteAdditionalOperator(
            Line_VersionStructure line,
            JAXBElement<? extends TransportOrganisationRefStructure> ref) {
        TransportOrganisationRefs_RelStructure ao = line.getAdditionalOperators();
        if (ao != null && !ao.getTransportOrganisationRef().isEmpty()) {
            ao.getTransportOrganisationRef().add(0, ref);
        } else {
            TransportOrganisationRefs_RelStructure fresh = new TransportOrganisationRefs_RelStructure();
            fresh.getTransportOrganisationRef().add(ref);
            line.setAdditionalOperators(fresh);
        }
    }
}
