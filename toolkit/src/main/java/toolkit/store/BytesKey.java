package toolkit.store;

import java.util.Arrays;

/// byte[] with value equality, for use as a map key.
public final class BytesKey {
    public final byte[] bytes;
    private final int hash;

    public BytesKey(byte[] bytes) {
        this.bytes = bytes;
        this.hash = Arrays.hashCode(bytes);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BytesKey k && hash == k.hash && Arrays.equals(bytes, k.bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
