package toolkit.transform.common;

// Which Line a ServiceJourney belongs to.
//
// A journey names its line in one of three ways, and a feed picks one: directly, through the Route
// it runs over, or through its ServiceJourneyPattern. A pattern in turn reaches the line through a
// routeRef or through a routeView that carries the lineRef itself.
//
// Neither index holds Lines. A lineRef's ref is already the line's id, and FlexibleLineRef
// substitutes into the same element, so a FlexibleLine resolves without being read.
//
// Union-field mapping: a single-slot choice field maps to a pair of sibling getters —
// getRouteRef() against getRouteView(). The line union is three-way: getLineRef() is a JAXBElement
// covering LineRef and FlexibleLineRef, against getLineView() / getFlexibleLineView().

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.LineRefStructure;
import noi.netex.model.Route;
import noi.netex.model.RouteView;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.ServiceJourneyPatternRefStructure;
import noi.netex.model.ServiceJourney_VersionStructure;
import toolkit.store.Store;
import toolkit.store.Txn;

import java.util.HashMap;
import java.util.Map;

public final class JourneyLines {

    private JourneyLines() {}

    /// The two hops a journey may take to its line: Route id to line id, and
    /// ServiceJourneyPattern id to line id.
    ///
    /// @param routeLine line id by Route id
    /// @param sjpLine line id by ServiceJourneyPattern id
    public record Index(Map<String, String> routeLine, Map<String, String> sjpLine) {}

    /// Build the index over `db` at `txn`. A Route or pattern that names no line is absent from it,
    /// so every lookup is a `Map.get` that may miss.
    public static Index index(Store db, Txn txn) {
        Map<String, String> routeLine = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, Route.class)) {
            Route route = (Route) o;
            String line = refOf(route.getLineRef());
            if (line != null) routeLine.put(route.getId(), line);
        }
        Map<String, String> sjpLine = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
            ServiceJourneyPattern sjp = (ServiceJourneyPattern) o;
            String line = null;
            if (sjp.getRouteRef() != null) {
                line = routeLine.get(sjp.getRouteRef().getRef());
            } else if (sjp.getRouteView() != null) {
                RouteView view = sjp.getRouteView();
                line = refOf(view.getLineRef());
            }
            if (line != null) sjpLine.put(sjp.getId(), line);
        }
        return new Index(routeLine, sjpLine);
    }

    /// The id of `sj`'s line, or null when none of the three routes to it resolves. The order is
    /// the journey's own lineRef, then its routeRef, then its journeyPatternRef.
    ///
    /// @param sj the journey to resolve
    /// @param ix the index [#index] built over the same store
    public static String lineIdOf(ServiceJourney_VersionStructure sj, Index ix) {
        String own = refOf(sj.getLineRef());
        if (own != null) return own;
        if (sj.getRouteRef() != null) return ix.routeLine().get(sj.getRouteRef().getRef());
        if (sj.getJourneyPatternRef() != null
                && sj.getJourneyPatternRef().getValue()
                        instanceof ServiceJourneyPatternRefStructure jpRef) {
            return ix.sjpLine().get(jpRef.getRef());
        }
        return null;
    }

    private static String refOf(JAXBElement<? extends LineRefStructure> el) {
        return el == null || el.getValue() == null ? null : el.getValue().getRef();
    }
}
