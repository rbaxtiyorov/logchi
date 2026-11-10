package io.github.rbaxtiyorov.logchi.core.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pauses file writes while free disk space is below the configured floor. Console output continues. */
public final class DiskGuard {

    private static final Logger log = LoggerFactory.getLogger("logchi");

    private final Path dir;
    private final long minFree;
    private volatile boolean paused;

    public DiskGuard(Path dir, long minFree) {
        this.dir = dir;
        this.minFree = minFree;
    }

    public boolean isPaused() {
        return paused;
    }

    /** Re-reads free space. Returns true if writes became paused on this call. */
    public boolean check() {
        if (minFree <= 0) {
            return false;
        }
        long free;
        try {
            free = usableSpace();
        } catch (IOException e) {
            return false;
        }
        boolean shouldPause = free < minFree;
        if (shouldPause == paused) {
            return false;
        }
        paused = shouldPause;
        if (shouldPause) {
            log.warn("logchi: free disk {} bytes is below min-free-disk {} bytes, pausing file logs", free, minFree);
        } else {
            log.info("logchi: free disk recovered to {} bytes, resuming file logs", free);
        }
        return shouldPause;
    }

    private long usableSpace() throws IOException {
        Path existing = dir.toAbsolutePath();
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("No existing parent of " + dir);
        }
        return Files.getFileStore(existing).getUsableSpace();
    }
}
