package toolkit.harness;

import toolkit.util.Log;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/// A zip an export writes entry by entry, with the entry metadata pinned and the byte/object totals
/// kept. No NeTEx and no profile knowledge: it takes names and bytes.
///
/// Pinning buys comparable metadata; a reproducible archive needs the content pinned too. Every
/// entry is stamped at the DOS epoch, so the headers carry no wall clock. The content carries one
/// only when the envelope is left to read it — see [NetexEnvelope] — and then so do the deflated
/// bytes. Compare exports with `tools/diff/export_zip.py`, which normalises
/// `<PublicationTimestamp>`; hash them only once that field is pinned.
///
/// `setTimeLocal`, not `setTime`: the latter round-trips through a timezone, so the same instant
/// lands on a different DOS field depending on where the exporter runs.
public final class ZipWriter implements AutoCloseable {

    /// The DOS epoch: 1980-01-01 00:00:00, the earliest timestamp a zip entry can carry.
    static final LocalDateTime DOS_EPOCH = LocalDateTime.of(1980, 1, 1, 0, 0, 0);

    private final ZipOutputStream zip;
    private final String tag;
    private String entryName;
    private long entryBytes;
    private long entryObjects;
    private long totalBytes;
    private long totalObjects;

    /// @param file  the file to write; the caller owns the atomic-rename dance around it
    /// @param level the deflate level, 0..9 — the caller validates the range
    /// @param tag   the driver tag the per-entry lines are logged under
    public ZipWriter(Path file, int level, String tag) throws IOException {
        // 1 MB buffer: a ZipOutputStream owns a single Deflater, which the write side must not
        // make wait.
        this.zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 20));
        this.zip.setLevel(level);
        this.tag = tag;
    }

    /// Start an entry with the pinned timestamp; the byte and object counters restart at zero.
    public void openEntry(String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(DOS_EPOCH);
        zip.putNextEntry(entry);
        entryName = name;
        entryBytes = 0;
        entryObjects = 0;
    }

    /// Finish the open entry, fold its counters into the totals and log the one-line summary.
    public void closeEntry() throws IOException {
        zip.closeEntry();
        totalBytes += entryBytes;
        totalObjects += entryObjects;
        Log.info("[%s] %s: %d objects, %.1f MB", tag, entryName, entryObjects, entryBytes / 1e6);
        entryName = null;
    }

    public void write(String s) throws IOException {
        write(s.getBytes(StandardCharsets.UTF_8));
    }

    public void write(byte[] b) throws IOException {
        zip.write(b);
        entryBytes += b.length;
    }

    /// One more object emitted into the open entry. Separate from [#write] because a fragment goes
    /// out as one array, but so do the container tags around it and the frame wrapper, and only the
    /// caller knows which of those is an object — the caller counts objects, this counts bytes.
    public void countObject() {
        entryObjects++;
    }

    public long totalBytes() {
        return totalBytes;
    }

    public long totalObjects() {
        return totalObjects;
    }

    @Override public void close() throws IOException {
        zip.close();
    }
}
