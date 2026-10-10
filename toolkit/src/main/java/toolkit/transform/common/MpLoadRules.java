package toolkit.transform.common;

// The zip-loader dedup rules, shaped as Store.MergeFn merge policies. Pure static functions that
// never open a store and never write: the loader registers them for the duration of a zip load.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.LocationStructure;
import noi.netex.model.Quay;
import noi.netex.model.Quays_RelStructure;
import noi.netex.model.StopPlace;
import noi.netex.model.TypeOfValue_VersionStructure;
import noi.netex.model.TypesOfValue_RelStructure;
import noi.netex.model.ValueSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MpLoadRules {

    private MpLoadRules() {}

    /// Union the embedded Quays of two copies of the same StopPlace. The INCOMING copy keeps every
    /// scalar field; quays present only in the STORED copy are appended sorted by id, and an
    /// incoming quay missing its centroid latitude adopts the stored copy's located centroid.
    /// Returns the merged incoming copy, or null when the stored copy adds nothing — null keeps the
    /// plain-replace path, verbatim fragment included.
    ///
    /// Why: some exports define a StopPlace in the stop registry AND in every line file that
    /// touches it, each copy embedding only the quays that file uses, so plain last-wins would
    /// discard all other copies' quays. The serial loaders and the fix stages replace without
    /// merging.
    public static Object mergeStopPlaceQuays(Object existing, Object incoming) {
        StopPlace base = (StopPlace) incoming;  // the NEW copy
        StopPlace extra = (StopPlace) existing; // the STORED copy
        List<JAXBElement<?>> baseQuays = base.getQuays() == null
                ? List.of() : base.getQuays().getQuayRefOrQuay();
        Map<String, Quay> have = new HashMap<>();
        for (JAXBElement<?> el : baseQuays) {
            if (el.getValue() instanceof Quay q) have.put(q.getId(), q); // duplicate id: last wins
        }
        List<JAXBElement<?>> extraQuays = extra.getQuays() == null
                ? List.of() : extra.getQuays().getQuayRefOrQuay();
        boolean changed = false;
        List<JAXBElement<?>> add = new ArrayList<>();
        for (JAXBElement<?> el : extraQuays) {
            if (!(el.getValue() instanceof Quay q)) continue;
            if (!have.containsKey(q.getId())) {
                add.add(el);
                continue;
            }
            Quay mine = have.get(q.getId());
            LocationStructure mineLoc = mine.getCentroid() == null ? null : mine.getCentroid().getLocation();
            LocationStructure extraLoc = q.getCentroid() == null ? null : q.getCentroid().getLocation();
            if ((mineLoc == null || mineLoc.getLatitude() == null)
                    && extraLoc != null && extraLoc.getLatitude() != null) {
                mine.setCentroid(q.getCentroid());
                changed = true;
            }
        }
        // stable sort by id; a null id sorts as "".
        add.sort(Comparator.comparing(el -> {
            String id = ((Quay) ((JAXBElement<?>) el).getValue()).getId();
            return id == null ? "" : id;
        }));
        if (add.isEmpty() && !changed) return null;
        if (base.getQuays() == null) base.setQuays(new Quays_RelStructure());
        base.getQuays().getQuayRefOrQuay().addAll(add);
        return base;
    }

    /// Union the nested members of two copies of the same ValueSet. The INCOMING copy keeps every
    /// scalar field; members present only in the STORED copy are appended sorted by id. Returns the
    /// merged incoming copy, or null when the stored copy adds nothing — null keeps the
    /// plain-replace path, verbatim fragment included.
    ///
    /// Why: a Mentz export declares one ValueSet id in every line file and populates it with only
    /// the members that file uses, so plain last-wins keeps one file's members and leaves every
    /// reference to the others dangling. The code list is complete only across the whole archive.
    ///
    /// The member class is never read, so one registration covers every code list a feed publishes
    /// this way.
    public static Object mergeValueSetValues(Object existing, Object incoming) {
        ValueSet base = (ValueSet) incoming;  // the NEW copy
        ValueSet extra = (ValueSet) existing; // the STORED copy
        Set<String> have = new HashSet<>();
        if (base.getValues() != null) {
            for (JAXBElement<? extends TypeOfValue_VersionStructure> el
                    : base.getValues().getTypeOfValue()) {
                if (el.getValue() != null) have.add(el.getValue().getId());
            }
        }
        List<JAXBElement<? extends TypeOfValue_VersionStructure>> add = new ArrayList<>();
        if (extra.getValues() != null) {
            for (JAXBElement<? extends TypeOfValue_VersionStructure> el
                    : extra.getValues().getTypeOfValue()) {
                if (el.getValue() != null && !have.contains(el.getValue().getId())) {
                    add.add(el);
                    have.add(el.getValue().getId());
                }
            }
        }
        if (add.isEmpty()) return null;
        // stable sort by id; a null id sorts as "".
        add.sort(Comparator.comparing(el -> {
            String id = el.getValue().getId();
            return id == null ? "" : id;
        }));
        if (base.getValues() == null) base.setValues(new TypesOfValue_RelStructure());
        base.getValues().getTypeOfValue().addAll(add);
        return base;
    }
}
