package io.github.rbaxtiyorov.logchi.core.appender;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.encoder.Encoder;
import io.github.rbaxtiyorov.logchi.core.LogchiSettings.Period;
import io.github.rbaxtiyorov.logchi.core.retention.DiskGuard;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Writes one sink to an active file and rolls it by period and size.
 *
 * <p>Compression and deletion happen elsewhere, in the janitor, so a roll is only a rename. I/O failures
 * never reach the application: the event is counted as lost and the file is retried a second later.
 * The file is opened on the first event, so sinks that never log leave no empty files.
 */
public final class LogchiFileAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final long RETRY_MILLIS = 1_000;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong lost = new AtomicLong();

    private final Path dir;
    private final FileNames names;
    private final String stem;
    private final String instance;
    private final Period period;
    private final long maxSize;
    private final Encoder<ILoggingEvent> encoder;
    private final DiskGuard guard;
    private final FileModes modes;

    private OutputStream out;
    private long size;
    private String periodKey;
    private long periodEnd;
    private long retryAt;
    private boolean failureReported;
    private long checkedAt;
    private Object fileKey;
    private int failures;
    private final java.util.Map<String, Integer> nextIndex = new java.util.HashMap<>();

    public LogchiFileAppender(Path dir, FileNames names, String stem, String instance, Period period, long maxSize,
                              Encoder<ILoggingEvent> encoder, DiskGuard guard) {
        this(dir, names, stem, instance, period, maxSize, encoder, guard, FileModes.DEFAULT);
    }

    public LogchiFileAppender(Path dir, FileNames names, String stem, String instance, Period period, long maxSize,
                              Encoder<ILoggingEvent> encoder, DiskGuard guard, FileModes modes) {
        this.modes = modes;
        this.dir = dir;
        this.names = names;
        this.stem = stem;
        this.instance = instance;
        this.period = period;
        this.maxSize = maxSize;
        this.encoder = encoder;
        this.guard = guard;
    }

    @Override
    public void start() {
        encoder.start();
        super.start();
    }

    @Override
    public void stop() {
        lock.lock();
        try {
            super.stop();
            closeQuietly();
        } finally {
            lock.unlock();
        }
        encoder.stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (guard.isPaused()) {
            lost.incrementAndGet();
            return;
        }
        byte[] bytes = encoder.encode(event);
        lock.lock();
        try {
            if (isStarted()) {
                write(bytes, event.getTimeStamp());
            }
        } finally {
            lock.unlock();
        }
    }

    /** Returns and resets the number of events that could not be written since the last call. */
    public long drainLost() {
        return lost.getAndSet(0);
    }

    private void write(byte[] bytes, long timestamp) {
        try {
            if (out != null && timestamp - checkedAt >= 1_000) {
                checkedAt = timestamp;
                // Someone removed or replaced the file under us. Writing on would go to a file nobody can read.
                if (!sameFile(dir.resolve(names.active(stem, instance)))) {
                    lost.incrementAndGet();
                    closeQuietly();
                    System.err.println("logchi: " + names.active(stem, instance) + " in " + dir
                            + " was removed or replaced, reopening it");
                }
            }
            if (out == null) {
                if (System.currentTimeMillis() < retryAt) {
                    lost.incrementAndGet();
                    return;
                }
                if (failures >= 3) {
                    // The file itself may be the problem, as when it hit a size limit. Set it aside.
                    failures = 0;
                    Path active = dir.resolve(names.active(stem, instance));
                    if (Files.exists(active) && Files.size(active) > 0) {
                        move(active, keyOf(timestamp));
                    }
                }
                open(timestamp);
            }
            if (timestamp >= periodEnd || (maxSize > 0 && size > 0 && size + bytes.length > maxSize)) {
                roll(timestamp);
            }
            out.write(bytes);
            size += bytes.length;
            failureReported = false;
            failures = 0;
        } catch (IOException | RuntimeException e) {
            fail(e);
        }
    }

    private void open(long now) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            modes.applyToDirectory(dir);
        }
        Path active = dir.resolve(names.active(stem, instance));
        // A file left by an earlier run is rolled under the period it was actually written in.
        if (period != Period.NONE && Files.exists(active) && Files.size(active) > 0) {
            String existingKey = keyOf(Files.getLastModifiedTime(active).toMillis());
            if (!existingKey.equals(keyOf(now))) {
                move(active, existingKey);
            }
        }
        boolean created = !Files.exists(active);
        out = new FileOutputStream(active.toFile(), true);
        if (created) {
            modes.applyToFile(active);
        }
        size = Files.size(active);
        fileKey = keyOfFile(active);
        checkedAt = now;
        if (size > 0 && !endsWithNewline(active, size)) {
            // A crash or a full disk cut the last event short. The next one must start on its own line.
            out.write('\n');
            size++;
        }
        periodKey = keyOf(now);
        periodEnd = endOf(now);
    }

    private void roll(long timestamp) throws IOException {
        String key = period == Period.NONE ? keyOf(timestamp) : periodKey;
        closeQuietly();
        move(dir.resolve(names.active(stem, instance)), key);
        open(timestamp);
    }

    private void move(Path active, String key) throws IOException {
        // Continue after the highest number in use. Reusing a number freed by retention would put a newer
        // file before older ones when they are read in name order.
        int index = nextIndex.computeIfAbsent(key, this::firstFreeIndex);
        Path target;
        do {
            target = dir.resolve(names.rolled(stem, instance, key, index++));
        } while (Files.exists(target) || Files.exists(target.resolveSibling(target.getFileName() + FileNames.GZ)));
        nextIndex.clear();
        nextIndex.put(key, index);
        try {
            Files.move(active, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(active, target);
        }
    }

    private static Object keyOfFile(Path file) {
        try {
            return Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        } catch (IOException e) {
            return null;
        }
    }

    private boolean sameFile(Path active) {
        if (!Files.exists(active)) {
            return false;
        }
        Object current = keyOfFile(active);
        return fileKey == null || current == null || fileKey.equals(current);
    }

    private int firstFreeIndex(String key) {
        String prefix = stem + "." + instance + "." + key + ".";
        int highest = -1;
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            for (Path path : files.toList()) {
                String file = path.getFileName().toString();
                if (file.startsWith(prefix)) {
                    int dot = file.indexOf('.', prefix.length());
                    try {
                        highest = Math.max(highest, Integer.parseInt(file.substring(prefix.length(), dot)));
                    } catch (RuntimeException notOurs) {
                        // A file that only looks similar.
                    }
                }
            }
        } catch (IOException e) {
            return 0;
        }
        return highest + 1;
    }

    private static boolean endsWithNewline(Path file, long size) {
        try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(file)) {
            java.nio.ByteBuffer last = java.nio.ByteBuffer.allocate(1);
            channel.position(size - 1);
            return channel.read(last) == 1 && last.get(0) == '\n';
        } catch (IOException e) {
            return true;
        }
    }

    private void fail(Exception e) {
        lost.incrementAndGet();
        failures++;
        closeQuietly();
        retryAt = System.currentTimeMillis() + RETRY_MILLIS;
        if (!failureReported) {
            failureReported = true;
            // Never log here: this runs inside the logging pipeline.
            System.err.println("logchi: cannot write " + names.active(stem, instance) + " in " + dir + ": " + e);
        }
    }

    private void closeQuietly() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // Nothing useful can be done with a failed close.
            }
            out = null;
        }
    }

    private String keyOf(long epochMillis) {
        Instant instant = Instant.ofEpochMilli(epochMillis);
        return (period == Period.HOURLY ? HOUR : DAY).format(instant.atZone(ZONE));
    }

    private long endOf(long epochMillis) {
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE);
        return switch (period) {
            case NONE -> Long.MAX_VALUE;
            case HOURLY -> time.truncatedTo(ChronoUnit.HOURS).plusHours(1).atZone(ZONE).toInstant().toEpochMilli();
            case DAILY -> LocalDate.from(time).plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        };
    }
}
