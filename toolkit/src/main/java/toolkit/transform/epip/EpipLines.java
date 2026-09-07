package toolkit.transform.epip;

// The EPIP line generator.
//
// additionalOperators.transportOrganisationRef is the one choice-group site: a
// List<JAXBElement<? extends TransportOrganisationRefStructure>> substitution-group list. The append
// wraps the ref object in its typed ObjectFactory element (createOperatorRef / createAuthorityRef)
// so the serialized element names are right; both OperatorRefStructure and AuthorityRef extend the
// list's bound.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.Line;
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
    /// a Line carries BOTH an operatorRef and an authorityRef, one of them moves into
    /// additionalOperators (appending when the list already exists, else creating it) and is
    /// cleared — which one is moved is decided by [EpipConfig#AUTHORITY_REFERENCE]: `true` keeps the
    /// Authority primary and demotes the Operator; `false` the reverse.
    ///
    /// Yield order: each Line in class-map order, processed lazily as it is pulled. The switch is
    /// read statically.
    public static Iterator<Object> epipLineGenerator(
            Store dbRead, Txn txnRead, GeneratorDefaults generatorDefaults) {
        Iterator<Object> lines = dbRead.iterOnlyObjects(txnRead, Line.class).iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return lines.hasNext();
            }

            @Override
            public Object next() {
                Line line = (Line) lines.next();
                process(line);
                return line;
            }
        };
    }

    private static void process(Line line) {
        line.setBrandingRef(null);
        line.setTypeOfServiceRef(null);
        line.setTypeOfProductCategoryRef(null);
        if (line.getOperatorRef() != null && line.getAuthorityRef() != null) {
            if (EpipConfig.AUTHORITY_REFERENCE) {
                appendAdditionalOperator(line, FACTORY.createOperatorRef(line.getOperatorRef()));
                line.setOperatorRef(null);
            } else {
                appendAdditionalOperator(line, FACTORY.createAuthorityRef(line.getAuthorityRef()));
                line.setAuthorityRef(null);
            }
        }
    }

    /// Append when additionalOperators exists AND its list is non-empty, else replace the container
    /// with a fresh one holding just this ref.
    private static void appendAdditionalOperator(
            Line line, JAXBElement<? extends TransportOrganisationRefStructure> ref) {
        TransportOrganisationRefs_RelStructure ao = line.getAdditionalOperators();
        if (ao != null && !ao.getTransportOrganisationRef().isEmpty()) {
            ao.getTransportOrganisationRef().add(ref);
        } else {
            TransportOrganisationRefs_RelStructure fresh = new TransportOrganisationRefs_RelStructure();
            fresh.getTransportOrganisationRef().add(ref);
            line.setAdditionalOperators(fresh);
        }
    }
}
