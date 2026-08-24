package toolkit.load;

import java.util.Set;
import java.util.TreeSet;

/// What a load inserted and skipped, as DATA rather than log lines — the harness reads it and
/// decides. One instance per stream, summed with [#plus] across a feed.
///
/// `unmappedNames` rides along because the count alone is not actionable. Distinct names, not
/// occurrences, so its size is bounded by the interesting set.
public record LoadStats(long objects, long frameDefaults, long skippedNoId, long unmapped,
                        long captureFallbacks, long nanos, Set<String> unmappedNames) {

    public LoadStats {
        // Sorted + unmodifiable, so a refusal message's element list does not reorder per run.
        unmappedNames = unmappedNames == null || unmappedNames.isEmpty()
                ? Set.of() : java.util.Collections.unmodifiableSet(new TreeSet<>(unmappedNames));
    }

    public static final LoadStats ZERO = new LoadStats(0, 0, 0, 0, 0, 0, Set.of());

    public LoadStats plus(LoadStats o) {
        Set<String> names;
        if (o.unmappedNames.isEmpty()) {
            names = unmappedNames;
        } else if (unmappedNames.isEmpty()) {
            names = o.unmappedNames;
        } else {
            names = new TreeSet<>(unmappedNames);
            names.addAll(o.unmappedNames);
        }
        return new LoadStats(objects + o.objects, frameDefaults + o.frameDefaults,
                skippedNoId + o.skippedNoId, unmapped + o.unmapped,
                captureFallbacks + o.captureFallbacks, nanos + o.nanos, names);
    }

    public double seconds() {
        return nanos / 1e9;
    }
}
