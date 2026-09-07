package toolkit.util;

// The probe must not eat its first element.
//
// EpipDbToXml wraps every member generator of every exported frame in a PeekIterator and builds the
// NeTEx rel-structure only when hasAny() says there is something in it, so the probe runs on every
// container of every split file. Getting it wrong raises no exception: the head element is consumed
// by the probe and the export is short by exactly one member per container, in valid XML that no
// schema check rejects.

import org.junit.jupiter.api.Test;
import toolkit.test.Check;

import java.util.ArrayList;
import java.util.List;

public class TestPeekIterator {

    /// The peeked head is handed back, so a probed stream is not short by one.
    @Test
    void testTheProbedHeadIsStillYielded() {
        PeekIterator<Integer> some = new PeekIterator<>(List.of(1, 2, 3));
        Check.that(some.hasAny(), "a non-empty stream probes true");
        Check.equals(List.of(1, 2, 3), drain(some), "the full stream, peeked head included");
    }

    /// hasAny caches its verdict; repeated probes must consume nothing further.
    @Test
    void testRepeatedProbesConsumeNothing() {
        PeekIterator<Integer> some = new PeekIterator<>(List.of(1, 2, 3));
        Check.that(some.hasAny(), "first probe");
        Check.that(some.hasAny(), "second probe");
        Check.that(some.hasAny(), "third probe");
        Check.equals(List.of(1, 2, 3), drain(some), "three probes cost no elements");
    }

    /// An empty stream probes false — repeatably — and iterates empty.
    @Test
    void testAnEmptyStreamProbesFalseAndStaysEmpty() {
        PeekIterator<Integer> empty = new PeekIterator<>(List.<Integer>of());
        Check.that(!empty.hasAny(), "an empty stream probes false");
        Check.that(!empty.hasAny(), "…and the false verdict is cached, not re-probed");
        Check.equals(List.of(), drain(empty), "an empty stream iterates empty");
    }

    /// Never probed: the plain stream, untouched.
    @Test
    void testAnUnprobedIteratorIsTheUnderlyingStream() {
        Check.equals(List.of(7, 8), drain(new PeekIterator<>(List.of(7, 8).iterator())),
                "iterating without probing yields the elements, not the generator object");
    }

    private static <T> List<T> drain(PeekIterator<T> it) {
        List<T> out = new ArrayList<>();
        for (T x : it) out.add(x);
        return out;
    }
}
