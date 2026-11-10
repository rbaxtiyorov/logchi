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

    private OutputStream out;
    private long size;
    private String periodKey;
    private long periodEnd;
    private long retryAt;
    private boolean failureReported;

    public LogchiFileAppender(Path dir, FileNames names, String stem, String instance, Period period, long maxSize,
                              Encoder<ILoggingEvent> encoder, DiskGuard guard) {
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
            if (out == null) {
                if (System.currentTimeMillis() < retryAt) {
                    lost.incrementAndGet();
                    return;
                }
                open(timestamp);
            }
            if (timestamp >= periodEnd || (maxSize > 0 && size > 0 && size + bytes.length > maxSize)) {
                roll(timestamp);
            }
            out.write(bytes);
            size += bytes.length;
            failureReported = false;
        } catch (IOException | RuntimeException e) {
            fail(e);
        }
    }

    private void open(long now) throws IOException {
        Files.createDirectories(dir);
        Path active = dir.resolve(names.active(stem, instance));
        // A file left by an earlier run is rolled under the period it was actually written in.
        if (period != Period.NONE && Files.exists(active) && Files.size(active) > 0) {
            String existingKey = keyOf(Files.getLastModifiedTime(active).toMillis());
            if (!existingKey.equals(keyOf(now))) {
                move(active, existingKey);
            }
        }
        out = new FileOutputStream(active.toFile(), true);
        size = Files.size(active);
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
        int index = 0;
        Path target;
        do {
            target = dir.resolve(names.rolled(stem, instance, key, index++));
        } while (Files.exists(target) || Files.exists(target.resolveSibling(target.getFileName() + FileNames.GZ)));
        try {
            Files.move(active, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(active, target);
        }
    }

    private void fail(Exception e) {
        lost.incrementAndGet();
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
