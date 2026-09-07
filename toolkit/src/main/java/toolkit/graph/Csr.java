package toolkit.graph;

import it.unimi.dsi.fastutil.longs.LongArrays;

import java.util.Arrays;

/// In-memory CSR adjacency, forward and inverse, over full_key edge pairs: ~30 bytes per edge
/// retained.
///
/// [#build] accepts edges in any order — pairs are co-sorted by (src, dst) and exact duplicates
/// dropped, matching the DUPSORT edge-map semantics where a pair stored twice is one row.
///
/// Arrays: nodes\[n\] sorted distinct full_keys; fwdOff\[n+1\]/fwdKeys\[E\] targets grouped by
/// referrer; invOff\[n+1\]/invKeys\[E\] referrers grouped by target. The int node-index mirrors are
/// not retained; an index is [#nodeIndex], a binary search over `nodes`.
public final class Csr {
    public final long[] nodes;
    public final long[] fwdOff, invOff;
    public final long[] fwdKeys, invKeys;
    public final int n, e;

    private Csr(long[] nodes, long[] fwdOff, long[] fwdKeys, long[] invOff, long[] invKeys) {
        this.nodes = nodes;
        this.fwdOff = fwdOff;
        this.fwdKeys = fwdKeys;
        this.invOff = invOff;
        this.invKeys = invKeys;
        this.n = nodes.length;
        this.e = fwdKeys.length;
    }

    /// Build from parallel src/dst full_key arrays (any order; duplicates collapse). Destructive on
    /// inputs.
    public static Csr build(long[] src, long[] dst) {
        if (src.length != dst.length) throw new IllegalArgumentException("src/dst length mismatch");
        int rawE = src.length;
        // co-sort pairs by (src, dst)
        LongArrays.parallelQuickSort(src, dst);
        // dedup identical pairs
        int E = 0;
        for (int i = 0; i < rawE; i++) {
            if (i > 0 && src[i] == src[E - 1] && dst[i] == dst[E - 1]) continue;
            src[E] = src[i];
            dst[E] = dst[i];
            E++;
        }
        // node table: sorted distinct endpoints
        long[] keys = new long[2 * E];
        System.arraycopy(src, 0, keys, 0, E);
        System.arraycopy(dst, 0, keys, E, E);
        Arrays.parallelSort(keys);
        int n = keys.length == 0 ? 0 : 1;
        for (int i = 1; i < keys.length; i++) if (keys[i] != keys[i - 1]) keys[n++] = keys[i];
        long[] nodes = Arrays.copyOf(keys, n);

        // forward CSR: input sorted by src -> offsets by merge-walk
        long[] fwdOff = new long[n + 1];
        int e = 0;
        for (int k = 0; k < n; k++) {
            fwdOff[k] = e;
            long key = nodes[k];
            while (e < E && src[e] == key) e++;
        }
        fwdOff[n] = e;
        if (e != E) throw new IllegalStateException("src key not in node table");
        long[] fwdKeys = Arrays.copyOf(dst, E);

        int[] dstIdx = new int[E];
        for (int i = 0; i < E; i++) dstIdx[i] = Arrays.binarySearch(nodes, fwdKeys[i]);

        // inverse CSR: counting sort by target
        long[] invOff = new long[n + 1];
        for (int i = 0; i < E; i++) invOff[dstIdx[i] + 1]++;
        for (int k = 0; k < n; k++) invOff[k + 1] += invOff[k];
        long[] invKeys = new long[E];
        int[] cur = new int[n];
        for (int i = 0; i < E; i++) {
            int t = dstIdx[i];
            invKeys[(int) invOff[t] + cur[t]++] = src[i];
        }
        return new Csr(nodes, fwdOff, fwdKeys, invOff, invKeys);
    }

    /// Node index of a full_key, or -1 when the key has no edges.
    public int nodeIndex(long fullKey) {
        int i = Arrays.binarySearch(nodes, fullKey);
        return i < 0 ? -1 : i;
    }

    /// Outward neighbor full_keys of a node (objects this full_key references).
    public long[] out(long fullKey) {
        int i = nodeIndex(fullKey);
        if (i < 0) return EMPTY;
        return Arrays.copyOfRange(fwdKeys, (int) fwdOff[i], (int) fwdOff[i + 1]);
    }

    /// Inward neighbor full_keys of a node (objects referencing this full_key).
    public long[] in(long fullKey) {
        int i = nodeIndex(fullKey);
        if (i < 0) return EMPTY;
        return Arrays.copyOfRange(invKeys, (int) invOff[i], (int) invOff[i + 1]);
    }

    private static final long[] EMPTY = new long[0];
}
