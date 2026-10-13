package toolkit.transform.feedfix;

// A destination stated in Name and nowhere a reader looks.

import noi.netex.model.DestinationDisplay;
import noi.netex.text.Mls;
import org.junit.jupiter.api.Test;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class TestDisplayTexts {

    private static DestinationDisplay display(String id, String front, String name) {
        DestinationDisplay dd = new DestinationDisplay();
        dd.setId(id);
        dd.setVersion("1");
        if (front != null) dd.setFrontText(Mls.of(front));
        if (name != null) dd.setName(Mls.of(name));
        return dd;
    }

    /// id to FrontText, over the whole store after the backfill is re-inserted.
    private static Map<String, String> frontTexts(TestStore ts, List<DestinationDisplay> in)
            throws Exception {
        try (Store store = Stores.create(ts.tempPath("dd.lmdb"), ts.format)) {
            try (Txn txn = store.rwTxn()) {
                store.insertAnyObjects(txn, List.copyOf(in));
                txn.commit();
            }
            try (Txn txn = store.rwTxn()) {
                store.insertAnyObjects(txn, DisplayTexts.backfillFrontText(store, txn).iterator());
                txn.commit();
            }
            Map<String, String> out = new TreeMap<>();
            try (Txn txn = store.roTxn()) {
                for (Object o : store.iterOnlyObjects(txn, DestinationDisplay.class)) {
                    DestinationDisplay dd = (DestinationDisplay) o;
                    out.put(dd.getId(), Mls.text(dd.getFrontText()));
                }
            }
            return out;
        }
    }

    @Test
    void testANameOnlyDisplayGetsItsFrontText(TestStore ts) throws Exception {
        Check.equals("Wil SG, Bahnhof",
                frontTexts(ts, List.of(display("dd:1", null, "Wil SG, Bahnhof"))).get("dd:1"),
                "the destination reaches the field a consumer reads");
    }

    /// The publisher's own FrontText wins. A display that states both is not this repair's subject,
    /// and overwriting it would substitute the longer form for the one the publisher chose to show.
    @Test
    void testADisplayWithItsOwnFrontTextIsUntouched(TestStore ts) throws Exception {
        Check.equals("Ried/Riedmark",
                frontTexts(ts, List.of(
                        display("dd:1", "Ried/Riedmark", "Ried/Riedmark via Greinsberg"))).get("dd:1"),
                "FrontText is kept, not replaced by the qualified Name");
    }

    @Test
    void testADisplayWithNeitherStaysEmpty(TestStore ts) throws Exception {
        Check.equals(null, frontTexts(ts, List.of(display("dd:1", null, null))).get("dd:1"),
                "nothing to copy, nothing invented");
    }

    /// A blank FrontText is an absent one: the publisher shipped the element and said nothing in it.
    @Test
    void testABlankFrontTextIsBackfilled(TestStore ts) throws Exception {
        Check.equals("Cheyres, école",
                frontTexts(ts, List.of(display("dd:1", "   ", "Cheyres, école"))).get("dd:1"),
                "whitespace is not a destination");
    }

    @Test
    void testOnlyTheChangedDisplaysComeBack(TestStore ts) throws Exception {
        try (Store store = Stores.create(ts.tempPath("dd-count.lmdb"), ts.format)) {
            try (Txn txn = store.rwTxn()) {
                store.insertAnyObjects(txn, List.of(
                        display("dd:1", null, "Alpha"),
                        display("dd:2", "Beta", "Beta via Gamma"),
                        display("dd:3", null, null)));
                txn.commit();
            }
            try (Txn txn = store.roTxn()) {
                Check.equals(1, DisplayTexts.backfillFrontText(store, txn).size(),
                        "one display needed the repair");
            }
        }
    }
}
