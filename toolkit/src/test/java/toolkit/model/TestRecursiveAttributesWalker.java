package toolkit.model;

// THE REFLECTION WALK, ASSERTED DIRECTLY.
//
// RecursiveAttributes.onlyReferences and embeddedEntities are the two extractions the insert path
// runs over every object it stores: what they return IS the unresolved-row set and the embedding
// index. The suite reaches them everywhere, but always through a store, where the observable is an
// edge — and an edge is what the walk found AND what resolve() then managed to wire. When the two
// are read together a walk that missed a reference and a resolve that could not place it look the
// same. These assert the walk's own output.
//
// The three properties that are not obvious from the shapes:
//
//   * THE TARGET NAME IS DERIVED FROM THE REF CLASS, not from the field it sits in and not from
//     nameOfRefClass, which real feeds usually omit: OperatorRefStructure -> "Operator" by
//     stripping the suffix, then checked against ModelRegistry. Getting this wrong does not throw,
//     it falls back to "DataManagedObject" and the reference resolves against the wrong class map.
//   * REFERENCES INSIDE REL-STRUCTURE LISTS COUNT. TrainNumberRef is not a field of ServiceJourney;
//     it is an element of a list inside TrainNumberRefs_RelStructure, and the descend() path that
//     reaches it (List, then JAXBElement) is separate from the plain-field one.
//   * DEDUP IS BY (targetClassName, ref, version). Smoke asserted a count of 2 over a fixture with
//     two DISTINCT references, which the dedup could not have affected either way; the fixture here
//     carries the same reference twice so the count is evidence.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.EntityStructure;
import noi.netex.model.Operator;
import noi.netex.model.OperatorRefStructure;
import noi.netex.model.Quay;
import noi.netex.model.Quays_RelStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.StopPlace;
import noi.netex.model.TrainNumberRefStructure;
import noi.netex.model.TrainNumberRefs_RelStructure;
import noi.netex.model.TrainNumber;
import noi.netex.model.ValidBetween;
import noi.netex.text.Mls;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;

import javax.xml.namespace.QName;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TestRecursiveAttributesWalker {

    /// A journey with an OperatorRef (a plain field, versioned) and TWO identical version-less
    /// TrainNumberRefs inside a rel-structure list.
    private static ServiceJourney journey() {
        ServiceJourney sj = new ServiceJourney();
        sj.setId("SJ:1");
        sj.setVersion("1");
        sj.setName(Mls.of("test journey"));
        OperatorRefStructure op = new OperatorRefStructure();
        op.setRef("O:1");
        op.setVersion("1");
        sj.setOperatorRef(op);
        sj.setTrainNumbers(new TrainNumberRefs_RelStructure()
                .withTrainNumberRef(trainNumberRef(), trainNumberRef()));
        return sj;
    }

    private static TrainNumberRefStructure trainNumberRef() {
        TrainNumberRefStructure tn = new TrainNumberRefStructure();
        tn.setRef("TN:1"); // no version: the id-only (tier 3) resolve arm
        return tn;
    }

    @Test
    void testTargetNamesComeFromTheRefClassAndListsAreWalked() {
        Set<String> got = new LinkedHashSet<>();
        for (RecursiveAttributes.Ref r : RecursiveAttributes.onlyReferences(journey())) {
            got.add(r.targetClassName() + "|" + r.ref() + "|" + r.version());
        }
        Check.that(got.contains("Operator|O:1|1"),
                "the OperatorRef's target name is derived from its ref class, got " + got);
        Check.that(got.contains("TrainNumber|TN:1|null"),
                "a reference inside a rel-structure list is reached, got " + got);
    }

    /// The registry, not a string trim, decides the name — so the derived name must be a real class.
    @Test
    void testTheDerivedNamesAreRegisteredClasses() {
        for (RecursiveAttributes.Ref r : RecursiveAttributes.onlyReferences(journey())) {
            Check.that(!"DataManagedObject".equals(r.targetClassName()),
                    "a derivable ref class must not fall back to DataManagedObject: " + r);
        }
        Check.equals(Operator.class, ModelRegistry.classForName("Operator"), "Operator is registered");
        Check.equals(TrainNumber.class, ModelRegistry.classForName("TrainNumber"),
                "TrainNumber is registered");
    }

    /// Two identical TrainNumberRefs are one tuple; three reference objects, two references.
    @Test
    void testIdenticalReferencesDedupToOneTuple() {
        ServiceJourney sj = journey();
        Check.equals(3, RecursiveAttributes.referenceObjects(sj).size(),
                "referenceObjects does NOT dedup: the two TrainNumberRefs are both there");
        Check.equals(2, RecursiveAttributes.onlyReferences(sj).size(),
                "onlyReferences dedups by (class, ref, version)");
    }

    /// A StopPlace whose Quay sits behind a JAXBElement inside a rel-structure list.
    private static StopPlace stopPlaceWithQuay() {
        Quay quay = new Quay();
        quay.setId("Q:1");
        StopPlace sp = new StopPlace();
        sp.setId("SP:1");
        sp.setVersion("1");
        Quays_RelStructure quays = new Quays_RelStructure();
        quays.getQuayRefOrQuay().add(new JAXBElement<>(
                new QName(ModelRegistry.NETEX_NS, "Quay"), Quay.class, quay));
        sp.setQuays(quays);
        return sp;
    }

    @Test
    void testAnEmbeddedEntityIsFoundThroughAJaxbElementList() {
        List<EntityStructure> embedded = RecursiveAttributes.embeddedEntities(stopPlaceWithQuay(), c -> true);
        Check.that(embedded.stream().anyMatch(e -> e instanceof Quay && "Q:1".equals(e.getId())),
                "the embedded Quay surfaces, got " + ids(embedded));
        Check.that(embedded.stream().noneMatch(e -> e instanceof StopPlace),
                "the ROOT is not one of its own embeddings, got " + ids(embedded));
    }

    /// The scope predicate is what keeps the persisted embedding index to the profile's classes;
    /// an id-carrying sub-object outside it must be skipped, not indexed and filtered later.
    @Test
    void testTheScopePredicateExcludes() {
        Check.equals(List.of(), ids(RecursiveAttributes.embeddedEntities(
                        stopPlaceWithQuay(), c -> c == ValidBetween.class)),
                "a scope that admits no class in the tree yields nothing");
        Check.equals(List.of("Quay|Q:1"), ids(RecursiveAttributes.embeddedEntities(
                        stopPlaceWithQuay(), c -> c == Quay.class)),
                "and a scope of exactly Quay yields exactly the Quay");
    }

    private static List<String> ids(List<EntityStructure> es) {
        return es.stream().map(e -> e.getClass().getSimpleName() + "|" + e.getId()).toList();
    }
}
