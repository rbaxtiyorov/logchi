package io.github.rbaxtiyorov.logchi.core.retention;

import io.github.rbaxtiyorov.logchi.core.LogchiSettings.Scope;
import io.github.rbaxtiyorov.logchi.core.appender.FileNames;
import io.github.rbaxtiyorov.logchi.core.instance.FileGuardLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JanitorTest {

    private static final long DAY = Duration.ofDays(1).toMillis();

    @TempDir
    Path dir;

    private final FileNames names = new FileNames("txt");
    private final Map<String, Janitor.Policy> policies = Map.of(
            "app", new Janitor.Policy(DAY, 30 * DAY),
            "app-error", new Janitor.Policy(DAY, 90 * DAY));

    private Janitor janitor(long cap, Scope scope) {
        return new Janitor(dir, "app", "me", names, policies, true, 6, cap, scope);
    }

    private Path file(String name, String content, Duration age) throws IOException {
        Path path = dir.resolve(name);
        Files.writeString(path, content);
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(age)));
        return path;
    }

    private List<String> files() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static String gunzip(Path path) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void compressesPastHotAndKeepsContentAndTimestamp() throws IOException {
        Path source = file("app.me.2026-01-01.0.txt", "hello\nworld\n", Duration.ofDays(2));
        FileTime original = Files.getLastModifiedTime(source);
        janitor(0, Scope.SHARED).run();
        Path gz = dir.resolve("app.me.2026-01-01.0.txt.gz");
        assertEquals(List.of(gz.getFileName().toString()), files());
        assertEquals("hello\nworld\n", gunzip(gz));
        assertEquals(original.toMillis() / 1000, Files.getLastModifiedTime(gz).toMillis() / 1000);
    }

    @Test
    void leavesFilesYoungerThanHotUncompressed() throws IOException {
        file("app.me.2026-01-01.0.txt", "x", Duration.ofHours(2));
        janitor(0, Scope.SHARED).run();
        assertEquals(List.of("app.me.2026-01-01.0.txt"), files());
    }

    @Test
    void deletesPastColdPerSink() throws IOException {
        file("app.me.2026-01-01.0.txt.gz", "x", Duration.ofDays(40));
        file("app-error.me.2026-01-01.0.txt.gz", "x", Duration.ofDays(40));
        file("app-error.me.2025-01-01.0.txt.gz", "x", Duration.ofDays(100));
        janitor(0, Scope.SHARED).run();
        assertEquals(List.of("app-error.me.2026-01-01.0.txt.gz"), files());
    }

    @Test
    void neverTouchesTheActiveFileOrForeignFiles() throws IOException {
        file("app.me.txt", "x", Duration.ofDays(400));
        file("notes.txt", "x", Duration.ofDays(400));
        file("other-app.me.2020-01-01.0.txt.gz", "x", Duration.ofDays(400));
        janitor(1, Scope.SHARED).run();
        assertEquals(List.of("app.me.txt", "notes.txt", "other-app.me.2020-01-01.0.txt.gz"), files());
    }

    @Test
    void capDeletesOldestFirst() throws IOException {
        file("app.me.2026-01-01.0.txt.gz", "1".repeat(100), Duration.ofDays(5));
        file("app.me.2026-01-02.0.txt.gz", "2".repeat(100), Duration.ofDays(4));
        file("app.me.2026-01-03.0.txt.gz", "3".repeat(100), Duration.ofDays(3));
        janitor(250, Scope.SHARED).run();
        assertEquals(List.of("app.me.2026-01-02.0.txt.gz", "app.me.2026-01-03.0.txt.gz"), files());
    }

    @Test
    void ownScopeLeavesOtherInstancesAlone() throws IOException {
        file("app.other.2026-01-01.0.txt.gz", "x", Duration.ofDays(40));
        file("app.me.2026-01-01.0.txt.gz", "x", Duration.ofDays(40));
        janitor(0, Scope.OWN).run();
        assertEquals(List.of("app.other.2026-01-01.0.txt.gz"), files());
    }

    @Test
    void sharedScopeCleansFilesOfReplacedPods() throws IOException {
        file("app.dead-pod.2026-01-01.0.txt.gz", "x", Duration.ofDays(40));
        janitor(0, Scope.SHARED).run();
        assertEquals(List.of(), files());
    }

    @Test
    void adoptsSilentActiveFileOfDeadInstance() throws IOException {
        file("app.dead-pod.txt", "last words\n", Duration.ofHours(2));
        janitor(0, Scope.SHARED).run();
        List<String> files = files();
        assertEquals(1, files.size(), files.toString());
        assertTrue(files.get(0).matches("app\\.dead-pod\\.\\d{4}-\\d{2}-\\d{2}\\.0\\.txt"), files.toString());
    }

    @Test
    void leavesActiveFileOfLiveInstanceAlone() throws IOException {
        file("app.live-pod.txt", "busy\n", Duration.ofHours(2));
        try (FileGuardLock held = FileGuardLock.tryAcquire(dir.resolve(".logchi/app.live-pod.lock"))) {
            assertTrue(held != null);
            janitor(0, Scope.SHARED).run();
        }
        assertEquals(List.of("app.live-pod.txt"), files());
    }

    @Test
    void leavesRecentActiveFileEvenIfLockLooksFree() throws IOException {
        file("app.other.txt", "x", Duration.ofMinutes(5));
        janitor(0, Scope.SHARED).run();
        assertEquals(List.of("app.other.txt"), files());
    }

    @Test
    void crashAfterRenameOnlyRemovesTheSource() throws IOException {
        file("app.me.2026-01-01.0.txt", "x", Duration.ofDays(2));
        Path gz = file("app.me.2026-01-01.0.txt.gz", "complete", Duration.ofDays(2));
        janitor(0, Scope.SHARED).run();
        assertEquals(List.of("app.me.2026-01-01.0.txt.gz"), files());
        assertEquals("complete", Files.readString(gz));
    }

    @Test
    void sweepsOldTempFilesFromCrashedCompression() throws IOException {
        file("app.me.2026-01-01.0.txt.gz.123.tmp", "partial", Duration.ofHours(2));
        file("app.me.2026-01-02.0.txt.gz.456.tmp", "in progress", Duration.ofMinutes(1));
        file("unrelated.gz.1.tmp", "foreign", Duration.ofHours(2));
        janitor(0, Scope.SHARED).run();
        assertEquals(List.of("app.me.2026-01-02.0.txt.gz.456.tmp", "unrelated.gz.1.tmp"), files());
    }

    @Test
    void removesStaleLocksOfGoneInstancesOnly() throws IOException {
        Path locks = Files.createDirectories(dir.resolve(".logchi"));
        Path stale = locks.resolve("app.gone.lock");
        Path recent = locks.resolve("app.starting.lock");
        Files.writeString(stale, "");
        Files.writeString(recent, "");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        janitor(0, Scope.SHARED).run();
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(recent));
    }

    @Test
    void reportsUsedBytes() throws IOException {
        file("app.me.txt", "12345", Duration.ZERO);
        file("app.me.2026-01-01.0.txt.gz", "123", Duration.ZERO);
        file("notes.txt", "ignored", Duration.ZERO);
        assertEquals(8, janitor(0, Scope.SHARED).usedBytes());
    }

    @Test
    void missingDirectoryIsNotAnError() {
        new Janitor(dir.resolve("absent"), "app", "me", names, policies, true, 6, 0, Scope.SHARED).runSafely();
        assertFalse(Files.exists(dir.resolve("absent")));
    }
}
