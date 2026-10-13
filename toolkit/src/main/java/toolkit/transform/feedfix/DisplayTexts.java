package toolkit.transform.feedfix;

import noi.netex.model.DestinationDisplay;
import noi.netex.text.Mls;
import toolkit.store.Store;
import toolkit.store.Txn;

import java.util.ArrayList;
import java.util.List;

/// A DestinationDisplay that states its text in `Name` and leaves `FrontText` empty.
///
/// `FrontText` is the field a consumer reads: it is what a vehicle shows and what a journey's
/// headsign is taken from, and the terminus naming pass reads it as one half of the pair a line is
/// named after. A publisher is free to put the destination in `Name` instead -- the element is a
/// `DataManagedObject` and `Name` is as conformant as any other field -- and a document that does
/// so names its destinations nowhere a reader looks.
///
/// Which feeds do it is the pipeline's to say; this only states what the repair is.
public final class DisplayTexts {

    private DisplayTexts() {}

    /// Every DestinationDisplay whose `FrontText` is absent or blank and whose `Name` is not, with
    /// the Name copied into FrontText. The rest are not returned: a caller re-inserts what it gets
    /// and a display already carrying FrontText is unchanged.
    ///
    /// `Name` is kept as well as copied. It is the field the publisher chose and nothing here knows
    /// that a consumer prefers one over the other.
    public static List<Object> backfillFrontText(Store db, Txn txn) {
        List<Object> changed = new ArrayList<>();
        for (Object o : db.iterOnlyObjects(txn, DestinationDisplay.class)) {
            DestinationDisplay dd = (DestinationDisplay) o;
            String front = Mls.text(dd.getFrontText());
            if (front != null && !front.isBlank()) continue;
            String name = Mls.text(dd.getName());
            if (name == null || name.isBlank()) continue;
            dd.setFrontText(Mls.of(name.strip()));
            changed.add(dd);
        }
        return changed;
    }
}
