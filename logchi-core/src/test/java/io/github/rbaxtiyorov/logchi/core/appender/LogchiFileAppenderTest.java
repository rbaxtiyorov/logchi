package io.github.rbaxtiyorov.logchi.core.appender;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import io.github.rbaxtiyorov.logchi.core.LogchiSettings;
import io.github.rbaxtiyorov.logchi.core.LogchiSettings.Period;
import io.github.rbaxtiyorov.logchi.core.encode.TextEncoder;
import io.github.rbaxtiyorov.logchi.core.mask.Masker;
import io.github.rbaxtiyorov.logchi.core.retention.DiskGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogchiFileAppenderTest {

    @TempDir
    Path dir;

    private final LoggerContext context = new LoggerContext();
    private final FileNames names = new FileNames("txt");
    private LogchiFileAppender appender;

    {
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
    }

    @AfterEach
    void stop() {
        if (appender != null) {
            appender.stop();
        }
    }

    private void start(Period period, long maxSize, DiskGuard guard) {
        TextEncoder encoder = new TextEncoder(Masker.NONE, 30, false);
        encoder.setContext(context);
        appender = new LogchiFileAppender(dir, names, "app", "i1", period, maxSize, encoder, guard);
        appender.setContext(context);
        appender.setName("test");
        appender.start();
    }

    private DiskGuard noGuard() {
        return new DiskGuard(dir, 0);
    }

    private void log(String message, long timestamp) {
        LoggingEvent e = new LoggingEvent("fqcn", context.getLogger("x"), Level.INFO, message, null, null);
        e.setTimeStamp(timestamp);
        appender.doAppend(e);
    }

    private List<String> files() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private long millis(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Test
    void writesNothingUntilTheFirstEvent() throws IOException {
        start(Period.DAILY, 0, noGuard());
        assertEquals(List.of(), files());
    }

    @Test
    void rollsAtSizeLimitKeepingEveryLine() throws IOException {
        start(Period.DAILY, 300, noGuard());
        long now = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) {
            log("message number " + i, now);
        }
        appender.stop();
        List<String> files = files();
        assertTrue(files.size() > 2, files.toString());
        assertTrue(files.contains("app.i1.txt"), files.toString());
        long lines = 0;
        for (String file : files) {
            assertTrue(Files.size(dir.resolve(file)) <= 300, file);
            lines += Files.readAllLines(dir.resolve(file)).size();
        }
        assertEquals(20, lines);
    }

    @Test
    void rollsWhenThePeriodChanges() throws IOException {
        start(Period.DAILY, 0, noGuard());
        LocalDate day = LocalDate.now();
        log("yesterday", millis(day.minusDays(1), 23));
        log("today", millis(day, 0));
        appender.stop();
        String rolled = "app.i1." + day.minusDays(1) + ".0.txt";
        assertEquals(List.of(rolled, "app.i1.txt"), files());
        assertTrue(Files.readString(dir.resolve(rolled)).contains("yesterday"));
        assertTrue(Files.readString(dir.resolve("app.i1.txt")).contains("today"));
    }

    @Test
    void hourlyPeriodUsesHourInTheName() throws IOException {
        start(Period.HOURLY, 0, noGuard());
        LocalDate day = LocalDate.now();
        log("a", millis(day, 10));
        log("b", millis(day, 11));
        appender.stop();
        assertTrue(files().contains("app.i1." + day + "-10.0.txt"), files().toString());
    }

    @Test
    void fileFromAnEarlierRunIsRolledUnderItsOwnDate() throws IOException {
        Path active = dir.resolve("app.i1.txt");
        Files.writeString(active, "old line\n");
        LocalDate old = LocalDate.now().minusDays(3);
        Files.setLastModifiedTime(active, FileTime.from(Instant.ofEpochMilli(millis(old, 12))));
        start(Period.DAILY, 0, noGuard());
        log("new line", System.currentTimeMillis());
        appender.stop();
        assertTrue(files().contains("app.i1." + old + ".0.txt"), files().toString());
        assertFalse(Files.readString(active).contains("old line"));
    }

    @Test
    void sameDayRestartAppends() throws IOException {
        start(Period.DAILY, 0, noGuard());
        log("first", System.currentTimeMillis());
        appender.stop();
        start(Period.DAILY, 0, noGuard());
        log("second", System.currentTimeMillis());
        appender.stop();
        assertEquals(List.of("app.i1.txt"), files());
        assertEquals(2, Files.readAllLines(dir.resolve("app.i1.txt")).size());
    }

    @Test
    void rollNeverOverwritesExistingArchive() throws IOException {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Files.writeString(dir.resolve("app.i1." + yesterday + ".0.txt.gz"), "archived");
        start(Period.DAILY, 0, noGuard());
        log("y", millis(yesterday, 10));
        log("t", System.currentTimeMillis());
        appender.stop();
        assertEquals("archived", Files.readString(dir.resolve("app.i1." + yesterday + ".0.txt.gz")));
        assertTrue(files().contains("app.i1." + yesterday + ".1.txt"), files().toString());
    }

    @Test
    void pausedGuardDropsAndCounts() throws IOException {
        DiskGuard guard = new DiskGuard(dir, Long.MAX_VALUE);
        assertTrue(guard.check());
        start(Period.DAILY, 0, guard);
        log("lost", System.currentTimeMillis());
        assertEquals(1, appender.drainLost());
        assertEquals(0, appender.drainLost());
        assertEquals(List.of(), files());
    }

    @Test
    void unwritableDirectoryNeverThrows() throws IOException {
        Path blocker = dir.resolve("not-a-dir");
        Files.writeString(blocker, "x");
        TextEncoder encoder = new TextEncoder(Masker.NONE, 30, false);
        encoder.setContext(context);
        appender = new LogchiFileAppender(blocker.resolve("logs"), names, "app", "i1", Period.DAILY, 0, encoder, noGuard());
        appender.setContext(context);
        appender.setName("test");
        appender.start();
        log("one", System.currentTimeMillis());
        log("two", System.currentTimeMillis());
        assertEquals(2, appender.drainLost());
    }

    @Test
    void concurrentWritersNeverInterleaveLines() throws Exception {
        start(Period.DAILY, 64 * 1024, noGuard());
        int threads = 8;
        int perThread = 2_000;
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread worker = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    log("t" + id + " i" + i + " end", System.currentTimeMillis());
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(30));
        }
        appender.stop();
        long lines = 0;
        for (String file : files()) {
            for (String line : Files.readAllLines(dir.resolve(file))) {
                assertTrue(line.matches(".* : t\\d+ i\\d+ end$"), line);
                lines++;
            }
        }
        assertEquals((long) threads * perThread, lines);
    }

    @Test
    void parsesTheNamesItWrites() {
        assertTrue(names.parse("app.i1.txt").orElseThrow().isActive());
        FileNames.Parsed rolled = names.parse("app-audit.pod-1.2026-11-08-10.3.txt.gz").orElseThrow();
        assertEquals("app-audit", rolled.stem());
        assertEquals("pod-1", rolled.instance());
        assertEquals("2026-11-08-10", rolled.periodKey());
        assertTrue(rolled.gzipped());
        assertTrue(names.parse("other.log").isEmpty());
        assertTrue(new FileNames("log").parse("app.i1.txt").isEmpty());
        assertEquals(LogchiSettings.Period.DAILY, new LogchiSettings().getFile().getRotation().getPeriod());
    }
}
