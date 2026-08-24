package toolkit.transform.epip;

// Infers ScheduledStopPoint locations from their Quay or StopPlace and applies them — EPIP
// generator 5. Its own file rather than ScheduledStopPoints, which holds the
// synthesize/backfill/plan cluster.
//
// Binding decisions:
//  - StopPlace.quays is getQuays().getQuayRefOrQuay(), a List<JAXBElement<?>>; an embedded Quay is
//    el.getValue() instanceof Quay.
//  - the PSA scheduled-stop-point union: getScheduledStopPointRef() value present AND NOT a
//    FareScheduledStopPointRefStructure, which is the sibling that must be excluded.
//  - the PSA quay union: getQuayRef() value present AND NOT a TaxiStandRefStructure.
//  - the PSA stop-place union: getStopPlaceRef() value present AND NOT a TaxiRankRefStructure.
//  - ssp.location, ssp.projections and ssp.stopAreas are plain fields.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.FareScheduledStopPointRefStructure;
import noi.netex.model.LocationStructure;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.QuayRefStructure;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPlaceRefStructure;
import noi.netex.model.TaxiRankRefStructure;
import noi.netex.model.TaxiStandRefStructure;
import toolkit.store.Store;
import toolkit.store.Txn;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

public final class EpipLocations {

    private EpipLocations() {}

    /// Index every Quay/StopPlace centroid location (a centroid-less Quay inherits its StopPlace's),
    /// map each assigned ScheduledStopPoint to its Quay's — or, last-wins, its StopPlace's —
    /// location, then re-emit EVERY ScheduledStopPoint with `projections`/`stopAreas` cleared and a
    /// missing `location` backfilled from the map. The index passes run eagerly, before any
    /// emission; only the SSP re-emit streams, its cursor opened on first pull.
    /// `generatorDefaults` is accepted and unused.
    public static Iterator<Object> inferLocationsFromQuayOrStopplaceAndApply(
            Store dbRead, Txn txn, GeneratorDefaults generatorDefaults) {
        Map<String, LocationStructure> mapping = new HashMap<>();
        for (Object o : dbRead.iterOnlyObjects(txn, StopPlace.class)) {
            StopPlace sp = (StopPlace) o;
            if (sp.getCentroid() != null) {
                mapping.put(sp.getId(), sp.getCentroid().getLocation());
            }
            if (sp.getQuays() != null) {
                for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
                    if (el.getValue() instanceof Quay quay) {
                        if (quay.getCentroid() != null) {
                            mapping.put(quay.getId(), quay.getCentroid().getLocation());
                        } else if (sp.getCentroid() != null) {
                            mapping.put(quay.getId(), sp.getCentroid().getLocation());
                        }
                    }
                }
            }
        }

        Map<String, LocationStructure> sspLocation = new HashMap<>();
        for (Object o : dbRead.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
            PassengerStopAssignment psa = (PassengerStopAssignment) o;
            JAXBElement<? extends ScheduledStopPointRefStructure> sspEl = psa.getScheduledStopPointRef();
            if (sspEl == null || sspEl.getValue() instanceof FareScheduledStopPointRefStructure) {
                continue;   // not a plain ScheduledStopPointRef (embedded SSP or Fare sibling)
            }
            String sspRef = sspEl.getValue().getRef();
            JAXBElement<? extends QuayRefStructure> quayEl = psa.getQuayRef();
            if (quayEl != null && !(quayEl.getValue() instanceof TaxiStandRefStructure)
                    && mapping.containsKey(quayEl.getValue().getRef())) {
                sspLocation.put(sspRef, mapping.get(quayEl.getValue().getRef()));
            }
            // the second test is NOT an else-if: a StopPlaceRef hit OVERWRITES the Quay hit.
            JAXBElement<? extends StopPlaceRefStructure> spEl = psa.getStopPlaceRef();
            if (spEl != null && !(spEl.getValue() instanceof TaxiRankRefStructure)
                    && mapping.containsKey(spEl.getValue().getRef())) {
                sspLocation.put(sspRef, mapping.get(spEl.getValue().getRef()));
            }
        }

        return new Iterator<>() {
            Iterator<Object> ssps;

            @Override
            public boolean hasNext() {
                if (ssps == null) ssps = dbRead.iterOnlyObjects(txn, ScheduledStopPoint.class).iterator();
                return ssps.hasNext();
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                ScheduledStopPoint ssp = (ScheduledStopPoint) ssps.next();
                // Neither belongs in an EPIP ScheduledStopPoint; cleared here because this pass
                // already re-emits every SSP.
                ssp.setProjections(null);
                ssp.setStopAreas(null);
                if (ssp.getLocation() == null) {
                    LocationStructure location = sspLocation.get(ssp.getId());
                    if (location != null) ssp.setLocation(location);
                }
                return ssp;
            }
        };
    }
}
