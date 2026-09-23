package toolkit.transform.epip;

// The EPIP line generator.
//
// additionalOperators.transportOrganisationRef is the one choice-group site: a
// List<JAXBElement<? extends TransportOrganisationRefStructure>> substitution-group list. The
// demotion wraps the ref object in its typed ObjectFactory element (createOperatorRef /
// createAuthorityRef) so the serialized element names are right; both OperatorRefStructure and
// AuthorityRef extend the list's bound.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.FlexibleLine;
import noi.netex.model.Line;
import noi.netex.model.Line_VersionStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.TransportOrganisationRefStructure;
import noi.netex.model.TransportOrganisationRefs_RelStructure;
import toolkit.store.Store;
import toolkit.store.Txn;

import java.util.Iterator;

public final class EpipLines {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    private EpipLines() {}

    /// Re-emit every Line with branding/type-of-service/type-of-product-category refs dropped; when
    /// a Line carries both an operatorRef and an authorityRef, one of them moves to the front of
    /// additionalOperators (creating the list when there is none) and is cleared — which one is
    /// moved is decided by [EpipConfig#AUTHORITY_REFERENCE]: `true` keeps the Authority primary and
    /// demotes the Operator; `false` the reverse.
    ///
    /// Yield order: each Line in class-map order, processed lazily as it is pulled. The switch is
    /// read statically.
    public static Iterator<Object> epipLineGenerator(
            Store dbRead, Txn txnRead, GeneratorDefaults generatorDefaults) {
        // Both concrete line classes. A FlexibleLine is a Line with flexible-service fields
        // added, so it carries the same organisation CHOICE and the same refs the profile has no
        // field for; draining one class map alone leaves every FlexibleLine unprocessed.
        //
        // Drained in turn rather than collected: the generator's contract is that it decodes one
        // object at a time, and a store holds every Line it has.
        Iterator<Object> plain = dbRead.iterOnlyObjects(txnRead, Line.class).iterator();
        Iterator<Object> flexible = dbRead.iterOnlyObjects(txnRead, FlexibleLine.class).iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return plain.hasNext() || flexible.hasNext();
            }

            @Override
            public Object next() {
                Line_VersionStructure line =
                        (Line_VersionStructure) (plain.hasNext() ? plain.next() : flexible.next());
                process(line);
                return line;
            }
        };
    }

    private static void process(Line_VersionStructure line) {
        line.setBrandingRef(null);
        line.setTypeOfServiceRef(null);
        line.setTypeOfProductCategoryRef(null);
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
