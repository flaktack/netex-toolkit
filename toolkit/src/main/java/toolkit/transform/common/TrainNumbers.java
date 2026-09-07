package toolkit.transform.common;

// TrainNumber synthesis, plus the Trenitalia-specific normalizeTrainNumber, trainNumberForJourney,
// tnId and updates. The generic half is synthesizeTrainNumbers, which takes the two policy
// functions; the other three are the Trenitalia arguments to it. The country policy is compiled
// into the jar because scripts/TrenitaliaDbToDb.java and scripts/TrenitaliaInPlace.java are
// source-launched and can reach nothing else.
//
// One streaming pass re-emits every ServiceJourney and interleaves a first-class TrainNumber
// before its first referrer, so the journey's TrainNumberRef resolves the moment it is inserted.
// One TrainNumber per distinct normalized number; journeys already carrying trainNumbers are
// skipped; only the small per-distinct-number map is retained, so the journeys themselves are
// never held in memory.

import noi.netex.text.Mls;
import noi.netex.model.MultilingualString;
import noi.netex.model.ServiceJourney;
import noi.netex.model.TrainNumber;
import noi.netex.model.TrainNumberRefStructure;
import noi.netex.model.TrainNumberRefs_RelStructure;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TrainNumbers {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private TrainNumbers() {}

    /// Normalise a train number: trim, take the first run of digits, drop leading zeros.
    public static String normalizeTrainNumber(String num) {
        if (num == null) return null;
        Matcher m = DIGITS.matcher(num.strip());
        return m.find() ? new BigInteger(m.group()).toString() : null;
    }

    /// The journey's train number, read off its name. The name is a mixed-content list, so
    /// [Mls#hasText] tests that the list is non-empty and [Mls#text] reads it — joining a
    /// multi-part carrier, where this site means the first content item.
    public static String trainNumberForJourney(ServiceJourney sj) {
        MultilingualString name = sj.getName();
        if (Mls.hasText(name)) {
            return normalizeTrainNumber(Mls.text(name));
        }
        return null;
    }

    /// The Trenitalia TrainNumber id scheme.
    private static String tnId(String num) {
        return "IT:TrainNumber:" + num;
    }

    /// One TrainNumber per distinct number found in a journey's name.
    public static Iterator<Object> updates(Store db, Txn txn) {
        return synthesizeTrainNumbers(db, txn, TrainNumbers::trainNumberForJourney,
                TrainNumbers::tnId, "trenitalia train-numbers");
    }

    /// A pull iterator. Yield order: for each journey, in class-map order, first the newly minted
    /// TrainNumber (if this journey's number is new), then the journey itself.
    public static Iterator<Object> synthesizeTrainNumbers(Store db, Txn txn,
            Function<ServiceJourney, String> numberForJourney, Function<String, String> tnId,
            String logTag) {
        Iterator<Object> journeys = db.iterOnlyObjects(txn, ServiceJourney.class).iterator();
        return new Iterator<>() {
            final Map<String, TrainNumber> trainNumbers = new LinkedHashMap<>();
            final Deque<Object> ready = new ArrayDeque<>(2);
            long linked = 0;
            boolean loggedDone;

            private void pump() {
                while (ready.isEmpty() && journeys.hasNext()) {
                    ServiceJourney sj = (ServiceJourney) journeys.next();
                    // Skip journeys that already carry train numbers.
                    String num = sj.getTrainNumbers() == null ? numberForJourney.apply(sj) : null;
                    if (num != null && !num.isEmpty()) {
                        TrainNumber tn = trainNumbers.get(num);
                        if (tn == null) {
                            tn = new TrainNumber();
                            tn.setId(tnId.apply(num));
                            tn.setVersion(sj.getVersion());
                            tn.setForAdvertisement(num);
                            trainNumbers.put(num, tn);
                            // Emit each TrainNumber before the journey that references it so
                            // refs resolve immediately (target-before-first-referrer).
                            ready.addLast(tn);
                        }
                        TrainNumberRefStructure ref = new TrainNumberRefStructure();
                        ref.setRef(tn.getId());
                        ref.setVersion(tn.getVersion());
                        TrainNumberRefs_RelStructure rel = new TrainNumberRefs_RelStructure();
                        rel.getTrainNumberRef().add(ref);
                        sj.setTrainNumbers(rel);
                        linked++;
                    }
                    ready.addLast(sj);
                }
                if (ready.isEmpty() && !loggedDone) {
                    loggedDone = true;
                    Log.info("[%s] linked %d journeys -> %d distinct train numbers",
                            logTag, linked, trainNumbers.size());
                }
            }

            @Override
            public boolean hasNext() {
                pump();
                return !ready.isEmpty();
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                return ready.removeFirst();
            }
        };
    }
}
