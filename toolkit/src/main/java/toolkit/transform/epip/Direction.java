package toolkit.transform.epip;

// Direction inference.
//
// This class shares its simple name with noi.netex.model.Direction, the object it synthesizes, so
// the model class is used fully qualified throughout.

import noi.netex.model.DirectionRefStructure;
import noi.netex.text.Mls;
import noi.netex.model.ServiceJourneyPattern;
import toolkit.harness.Ensemble;
import toolkit.model.NetexUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Direction {

    private Direction() {}

    /// The process and directions that direction inference returns.
    ///
    /// Drain-after contract: [#op] populates [#inferred] lazily DURING the ensemble pass — a
    /// Direction is synthesized the first time its direction type is seen. Obtain
    /// `inferred().values().iterator()` only AFTER the [Ensemble#applyOperations] iterator has been
    /// fully drained (e.g. two sequential `insertAnyObjects` calls on the same txn); iterating the
    /// values view concurrently with the pass throws `ConcurrentModificationException` and misses
    /// later insertions.
    public record InferDirections(Ensemble.Operation op,
                                  Map<String, noi.netex.model.Direction> inferred) {}

    /// Returns `(process, directions)` where `process(sjp) -> changed` backfills a DirectionRef from
    /// the direction type (synthesising the shared Direction on first use) and `directions` holds
    /// the synthesised Direction objects — drain it AFTER the ensemble pass (see
    /// [InferDirections]).
    ///
    /// The binding types the field as [noi.netex.model.DirectionTypeEnumeration] always, so the key
    /// is uniformly the enumeration's XML value (`"inbound"`, `"outbound"`, …).
    public static InferDirections inferDirectionsOp(GeneratorDefaults generatorDefaults) {
        Map<String, noi.netex.model.Direction> directions = new LinkedHashMap<>();
        Map<String, DirectionRefStructure> directionRefs = new LinkedHashMap<>();

        Ensemble.Operation process = obj -> {
            ServiceJourneyPattern sjp = (ServiceJourneyPattern) obj;
            if (sjp.getDirectionType() != null
                    && sjp.getDirectionRef() == null && sjp.getDirectionView() == null) {
                String key = sjp.getDirectionType().value();
                noi.netex.model.Direction direction = directions.get(key);
                if (direction == null) {
                    direction = new noi.netex.model.Direction();
                    direction.setId(NetexUtils.getId(
                            generatorDefaults.codespace(), noi.netex.model.Direction.class, key));
                    direction.setVersion(sjp.getVersion());
                    direction.setName(Mls.of(key));
                    direction.setDirectionType(sjp.getDirectionType());
                    directions.put(key, direction);
                    // No DirectionRef class in the model, so getRef resolves to
                    // DirectionRefStructure.
                    directionRefs.put(key, (DirectionRefStructure) NetexUtils.getRef(direction));
                }
                sjp.setDirectionRef(directionRefs.get(key));
                return true;
            }
            return false;
        };

        return new InferDirections(process, directions);
    }
}
