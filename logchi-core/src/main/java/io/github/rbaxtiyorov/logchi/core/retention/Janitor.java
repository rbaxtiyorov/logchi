package io.github.rbaxtiyorov.logchi.core.retention;

import io.github.rbaxtiyorov.logchi.core.LogchiSettings.Scope;
import io.github.rbaxtiyorov.logchi.core.appender.FileModes;
import io.github.rbaxtiyorov.logchi.core.appender.FileNames;
import io.github.rbaxtiyorov.logchi.core.instance.FileGuardLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Enforces retention: gzips files past {@code hot}, deletes files past {@code cold}, enforces the total cap.
 *
 * <p>Ages count from a file's last write. In {@code SHARED} scope every instance also looks after files
 * left by dead instances, which is what cleans up after replaced Kubernetes pods. A lock keeps janitors
 * from doing the same work at once, and every step is safe to repeat if that lock is unreliable, as it
 * can be on network file systems.
 */
public final class Janitor {

    /** How long a dead instance's active file must be silent before it is adopted. Guards against unreliable locks. */
    static final long ORPHAN_SILENCE_MILLIS = 3_600_000L;
    private static final long TMP_MAX_AGE_MILLIS = 3_600_000L;
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private static final Logger log = LoggerFactory.getLogger("logchi");

    public record Policy(long hotMillis, long coldMillis) {
    }

    private record Entry(Path path, FileNames.Parsed name, long size, long modified) {
    }

    private final Path dir;
    private final Path lockDir;
    private final String appName;
    private final String instance;
    private final FileNames names;
    private final Map<String, Policy> policies;
    private final boolean compress;
    private final long maxTotal;
    private final Scope scope;
    private final Gzipper gzipper;
    private final FileModes modes;

    public Janitor(Path dir, String appName, String instance, FileNames names, Map<String, Policy> policies,
                   boolean compress, int compressLevel, long maxTotal, Scope scope) {
        this(dir, appName, instance, names, policies, compress, compressLevel, maxTotal, scope, FileModes.DEFAULT);
    }

    public Janitor(Path dir, String appName, String instance, FileNames names, Map<String, Policy> policies,
                   boolean compress, int compressLevel, long maxTotal, Scope scope, FileModes modes) {
        this.dir = dir;
        this.lockDir = dir.resolve(".logchi");
        this.appName = appName;
        this.instance = instance;
        this.names = names;
        this.policies = policies;
        this.compress = compress;
        this.maxTotal = maxTotal;
        this.scope = scope;
        this.gzipper = new Gzipper(compressLevel, modes);
        this.modes = modes;
    }

    /** Runs one pass and contains every failure, since an exception would cancel the schedule. */
    public void runSafely() {
        try {
            run();
        } catch (Exception e) {
            log.warn("logchi: retention pass failed: {}", e.toString());
        }
    }

    public synchronized void run() throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        if (scope == Scope.OWN) {
            pass();
            return;
        }
        FileGuardLock lock;
        try {
            lock = FileGuardLock.tryAcquire(lockDir.resolve(appName + ".janitor.lock"));
        } catch (IOException e) {
            // The lock folder is unusable. Every step is safe to repeat, so run without the lock.
            pass();
            return;
        }
        if (lock != null) {
            try (lock) {
                modes.applyToFile(lockDir.resolve(appName + ".janitor.lock"));
                pass();
            }
        }
    }

    /** Bytes used by this application's log files, active ones included. */
    public long usedBytes() {
        try {
            return scan().stream().mapToLong(Entry::size).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private void pass() throws IOException {
        long now = System.currentTimeMillis();
        sweepTempFiles(now);
        adoptOrphans(now);
        List<Entry> survivors = new ArrayList<>();
        for (Entry entry : scan()) {
            if (entry.name().isActive()) {
                survivors.add(entry);
                continue;
            }
            Policy policy = policies.get(entry.name().stem());
            long age = now - entry.modified();
            try {
                if (age >= policy.coldMillis()) {
                    Files.deleteIfExists(entry.path());
                } else if (compress && !entry.name().gzipped() && age >= policy.hotMillis()) {
                    gzipper.compress(entry.path());
                    survivors.add(rescan(entry));
                } else {
                    survivors.add(entry);
                }
            } catch (NoSuchFileException gone) {
                // Another janitor got there first.
            } catch (IOException e) {
                survivors.add(entry);
                log.warn("logchi: could not process {}: {}", entry.path().getFileName(), e.toString());
            }
        }
        enforceCap(survivors);
    }

    /** Re-reads a just-compressed file so the cap sees its real, smaller size. */
    private Entry rescan(Entry entry) throws IOException {
        Path gz = entry.path().resolveSibling(entry.path().getFileName() + FileNames.GZ);
        return new Entry(gz, entry.name(), Files.size(gz), entry.modified());
    }

    private void enforceCap(List<Entry> entries) {
        if (maxTotal <= 0) {
            return;
        }
        long total = entries.stream().mapToLong(Entry::size).sum();
        if (total <= maxTotal) {
            return;
        }
        List<Entry> oldestFirst = entries.stream()
                .filter(e -> !e.name().isActive())
                .sorted(Comparator.comparingLong(Entry::modified))
                .toList();
        for (Entry entry : oldestFirst) {
            if (total <= maxTotal) {
                return;
            }
            try {
                Files.deleteIfExists(entry.path());
                total -= entry.size();
            } catch (IOException e) {
                log.warn("logchi: could not delete {}: {}", entry.path().getFileName(), e.toString());
            }
        }
    }

    /** Rolls active files whose owner is gone. Nobody else would ever compress or expire them. */
    private void adoptOrphans(long now) throws IOException {
        if (scope == Scope.OWN) {
            return;
        }
        for (Entry entry : scan()) {
            FileNames.Parsed name = entry.name();
            if (!name.isActive() || name.instance().equals(instance)
                    || now - entry.modified() < ORPHAN_SILENCE_MILLIS) {
                continue;
            }
            Path lockFile = lockDir.resolve(appName + "." + name.instance() + ".lock");
            try (FileGuardLock lock = FileGuardLock.tryAcquire(lockFile)) {
                if (lock == null) {
                    continue;
                }
                rollOrphan(entry);
                // Deleted while still held, so nobody can take it in between.
                Files.deleteIfExists(lockFile);
            }
        }
        sweepStaleLocks(now);
    }

    /** Removes lock files of instances that are gone, which would otherwise pile up as pods are replaced. */
    private void sweepStaleLocks(long now) throws IOException {
        if (!Files.isDirectory(lockDir)) {
            return;
        }
        String own = appName + "." + instance + ".lock";
        String janitorLock = appName + ".janitor.lock";
        try (Stream<Path> files = Files.list(lockDir)) {
            for (Path path : files.toList()) {
                String file = path.getFileName().toString();
                if (!file.startsWith(appName + ".") || !file.endsWith(".lock") || file.equals(own)
                        || file.equals(janitorLock)
                        || now - Files.getLastModifiedTime(path).toMillis() < ORPHAN_SILENCE_MILLIS) {
                    continue;
                }
                try (FileGuardLock lock = FileGuardLock.tryAcquire(path)) {
                    if (lock == null) {
                        continue;
                    }
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void rollOrphan(Entry entry) throws IOException {
        FileNames.Parsed name = entry.name();
        String key = DAY.format(Instant.ofEpochMilli(entry.modified()));
        int index = 0;
        Path target;
        do {
            target = dir.resolve(names.rolled(name.stem(), name.instance(), key, index++));
        } while (Files.exists(target) || Files.exists(target.resolveSibling(target.getFileName() + FileNames.GZ)));
        try {
            Files.move(entry.path(), target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(entry.path(), target);
        }
    }

    /** Removes temp files left by a crash mid-compression. The source file is still intact. */
    private void sweepTempFiles(long now) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files.toList()) {
                String file = path.getFileName().toString();
                if (file.endsWith(Gzipper.TMP_SUFFIX) && file.contains(FileNames.GZ + ".")
                        && isOurs(file.substring(0, file.indexOf(FileNames.GZ + ".") + FileNames.GZ.length()))
                        && now - Files.getLastModifiedTime(path).toMillis() > TMP_MAX_AGE_MILLIS) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private boolean isOurs(String fileName) {
        return names.parse(fileName).filter(this::inScope).isPresent();
    }

    private boolean inScope(FileNames.Parsed name) {
        return policies.containsKey(name.stem()) && (scope == Scope.SHARED || name.instance().equals(instance));
    }

    private List<Entry> scan() throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files.toList()) {
                var parsed = names.parse(path.getFileName().toString()).filter(this::inScope);
                if (parsed.isEmpty()) {
                    continue;
                }
                try {
                    entries.add(new Entry(path, parsed.get(), Files.size(path),
                            Files.getLastModifiedTime(path).toMillis()));
                } catch (NoSuchFileException gone) {
                    // Rolled or deleted between listing and reading.
                }
            }
        }
        return entries;
    }
}
