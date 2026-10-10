package toolkit.transform.common;

// The code lists a document defines, read from both places a publisher puts them.
//
// A code list is usually published as a ValueSet holding its members, which leaves the standalone
// rows of the member class empty while every id a referrer names sits inside the ValueSet. Reading
// only the rows finds none of them.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.TypeOfValue_VersionStructure;
import noi.netex.model.ValueSet;
import toolkit.store.Store;
import toolkit.store.Txn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TypeOfValues {

    private TypeOfValues() {}

    /// Every definition of the named classes the document will carry, keyed by id: the standalone
    /// rows of each class, then every member nested inside a `ValueSet`. A nested member wins an id
    /// a standalone row also claims.
    ///
    /// Each name in `classNames` has to name a `TypeOfValue` subclass; a name the store does not
    /// hold is skipped.
    public static Map<String, TypeOfValue_VersionStructure> index(
            Store db, Txn txn, List<String> classNames) {
        Map<String, TypeOfValue_VersionStructure> out = new LinkedHashMap<>();
        for (String n : classNames) {
            Class<?> c = db.classForName(n);
            if (c == null) continue;
            for (Object o : db.iterOnlyObjects(txn, c)) {
                TypeOfValue_VersionStructure v = (TypeOfValue_VersionStructure) o;
                if (v.getId() != null) out.put(v.getId(), v);
            }
        }
        if (db.classForName("ValueSet") != null) {
            for (Object o : db.iterOnlyObjects(txn, ValueSet.class)) {
                ValueSet vs = (ValueSet) o;
                if (vs.getValues() == null) continue;
                for (JAXBElement<? extends TypeOfValue_VersionStructure> el
                        : vs.getValues().getTypeOfValue()) {
                    TypeOfValue_VersionStructure v = el.getValue();
                    if (v != null && v.getId() != null) out.put(v.getId(), v);
                }
            }
        }
        return out;
    }

    /// The ids [#index] finds, for a caller that only tests whether a reference resolves.
    public static Set<String> definedIds(Store db, Txn txn, List<String> classNames) {
        return index(db, txn, classNames).keySet();
    }
}
