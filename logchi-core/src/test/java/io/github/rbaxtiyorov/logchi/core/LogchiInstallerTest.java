package io.github.rbaxtiyorov.logchi.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogchiInstallerTest {

    @TempDir
    Path dir;

    private final LoggerContext context = new LoggerContext();
    private Logger logger;

    @BeforeEach
    void setUp() {
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        context.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        logger = context.getLogger("com.example.Service");
    }

    @AfterEach
    void tearDown() {
        LogchiInstaller.uninstall();
    }

    private LogchiSettings settings() {
        LogchiSettings s = new LogchiSettings();
        s.setDir(dir.toString());
        s.setAppName("orders");
        s.setInstanceId("pod-1");
        s.getConsole().setEnabled(false);
        s.getFile().getRetention().setMinFreeDisk("0");
        return s;
    }

    private String read(String file) throws IOException {
        return Files.readString(dir.resolve(file));
    }

    private List<String> files() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "condition not met in time");
    }

    @Test
    void writesMainAndErrorFiles() throws IOException {
        LogchiInstaller.install(context, settings());
        logger.info("all good");
        logger.error("went wrong");
        LogchiInstaller.uninstall();
        assertEquals(List.of("orders-error.pod-1.txt", "orders.pod-1.txt"), files());
        assertTrue(read("orders.pod-1.txt").contains("all good"));
        assertTrue(read("orders.pod-1.txt").contains("went wrong"));
        assertFalse(read("orders-error.pod-1.txt").contains("all good"));
        assertTrue(read("orders-error.pod-1.txt").contains("went wrong"));
    }

    @Test
    void uninstallFlushesEveryQueuedEvent() throws IOException {
        LogchiInstaller.install(context, settings());
        for (int i = 0; i < 1_000; i++) {
            logger.warn("line {}", i);
        }
        LogchiInstaller.uninstall();
        assertEquals(1_000, Files.readAllLines(dir.resolve("orders.pod-1.txt")).size());
    }

    @Test
    void channelsGoToTheirOwnFileOnly() throws IOException {
        LogchiSettings s = settings();
        LogchiSettings.Sink audit = new LogchiSettings.Sink();
        audit.setCold("365d");
        audit.setAsync(false);
        audit.setFormat(LogchiSettings.Format.JSON);
        s.getChannels().put("audit", audit);
        LogchiInstaller.install(context, s);
        context.getLogger(Channels.PREFIX + "audit").info("refund approved");
        logger.info("normal");
        LogchiInstaller.uninstall();
        assertTrue(read("orders-audit.pod-1.txt").startsWith("{"));
        assertTrue(read("orders-audit.pod-1.txt").contains("refund approved"));
        assertFalse(read("orders.pod-1.txt").contains("refund approved"));
    }

    @Test
    void extensionIsConfigurable() throws IOException {
        LogchiSettings s = settings();
        s.getFile().setExtension("log");
        LogchiInstaller.install(context, s);
        logger.info("x");
        LogchiInstaller.uninstall();
        assertTrue(files().contains("orders.pod-1.log"), files().toString());
    }

    @Test
    void levelSinkCanBeDisabled() throws IOException {
        LogchiSettings s = settings();
        s.getLevels().get("error").setEnabled(false);
        LogchiInstaller.install(context, s);
        logger.error("x");
        LogchiInstaller.uninstall();
        assertEquals(List.of("orders.pod-1.txt"), files());
    }

    @Test
    void masksInFiles() throws IOException {
        LogchiInstaller.install(context, settings());
        logger.info("login password=hunter2");
        LogchiInstaller.uninstall();
        assertFalse(read("orders.pod-1.txt").contains("hunter2"));
    }

    @Test
    void reattachesAfterLogbackReset() throws Exception {
        LogchiInstaller.install(context, settings());
        logger.info("before");
        context.reset();
        context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        await(() -> context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders().hasNext());
        logger.info("after");
        LogchiInstaller.uninstall();
        assertTrue(read("orders.pod-1.txt").contains("before"));
        assertTrue(read("orders.pod-1.txt").contains("after"));
    }

    /** The sequence Spring Boot performs when it reinitializes logging, as Spring Cloud triggers. */
    @Test
    void reattachesAfterStopAndRestart() throws Exception {
        LogchiInstaller.install(context, settings());
        logger.info("before");
        context.stop();
        context.reset();
        context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        context.start();
        await(() -> context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders().hasNext());
        logger.info("after");
        LogchiInstaller.uninstall();
        assertTrue(read("orders.pod-1.txt").contains("before"));
        assertTrue(read("orders.pod-1.txt").contains("after"));
    }

    /** Once the console is back, a line logged at once must reach the file too, so files have to be back first. */
    @Test
    void filesAreBackBeforeTheConsoleAfterAReset() {
        LogchiSettings s = settings();
        s.getConsole().setEnabled(true);
        LogchiInstaller.install(context, s);
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        for (int round = 0; round < 3; round++) {
            context.reset();
            root.setLevel(Level.INFO);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (root.getAppender("logchi-console") == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertNotNull(root.getAppender("logchi-console"), "appenders must come back after a reset");
            assertNotNull(root.getAppender("logchi-async-orders"), "the file must be attached before the console");
        }
    }

    @Test
    void doesNotReattachAfterRealShutdown() throws Exception {
        LogchiInstaller.install(context, settings());
        context.stop();
        Thread.sleep(2_500);
        assertFalse(context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders().hasNext());
    }

    @Test
    void reinstallReplacesWithoutDuplicates() throws IOException {
        LogchiInstaller.install(context, settings());
        LogchiInstaller.install(context, settings());
        logger.info("once");
        LogchiInstaller.uninstall();
        assertEquals(1, Files.readAllLines(dir.resolve("orders.pod-1.txt")).size());
    }

    @Test
    void invalidConfigFailsWithAClearMessage() {
        LogchiSettings s = settings();
        s.getFile().getRetention().setHot("soon");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LogchiInstaller.install(context, s));
        assertTrue(e.getMessage().contains("soon"));
    }

    @Test
    void unknownLevelFailsWithAClearMessage() {
        LogchiSettings s = settings();
        s.getLevels().put("loud", new LogchiSettings.Sink());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LogchiInstaller.install(context, s));
        assertTrue(e.getMessage().contains("loud"));
    }

    @Test
    void summaryDescribesTheInstallation() {
        LogchiInstaller.install(context, settings());
        String summary = LogchiInstaller.summary().orElseThrow();
        assertTrue(summary.contains("instance=pod-1"), summary);
        assertTrue(summary.contains("levels=[error]"), summary);
    }

    @Test
    void disabledInstallsNothing() {
        LogchiSettings s = settings();
        s.setEnabled(false);
        LogchiInstaller.install(context, s);
        assertTrue(LogchiInstaller.summary().isEmpty());
        assertFalse(context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders().hasNext());
    }

    @Test
    void parsesFriendlyValues() {
        assertEquals(500, Parse.durationMillis("500ms"));
        assertEquals(2_000, Parse.durationMillis("2s"));
        assertEquals(86_400_000L * 30, Parse.durationMillis("30d"));
        assertEquals(100L << 20, Parse.bytes("100MB"));
        assertEquals(5L << 30, Parse.bytes("5gb"));
        assertEquals(0, Parse.bytes("0"));
        assertEquals("1.5KB", Parse.humanBytes(1536));
    }

    @Test
    void compressLevelIsCheckedAtStartup() {
        LogchiSettings s = settings();
        s.getFile().getRetention().setCompressLevel(10);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LogchiInstaller.install(context, s));
        assertTrue(e.getMessage().contains("compress-level"), e.getMessage());
    }

    @Test
    void withoutFilesTheFolderIsNotTouched() {
        LogchiSettings s = settings();
        s.setDir(dir.resolve("never").toString());
        s.getFile().setEnabled(false);
        s.getConsole().setEnabled(true);
        LogchiInstaller.install(context, s);
        logger.info("console only");
        LogchiInstaller.uninstall();
        assertFalse(Files.exists(dir.resolve("never")), "no .logchi folder without files");
    }

    @Test
    void existingLoggersCanBeRoutedToAChannel() throws IOException {
        LogchiSettings s = settings();
        LogchiSettings.Sink http = new LogchiSettings.Sink();
        http.setAsync(false);
        http.setLoggers(List.of("com.example.web.HttpLoggingFilter"));
        s.getChannels().put("http", http);
        LogchiInstaller.install(context, s);
        context.getLogger("com.example.web.HttpLoggingFilter").info("REQ GET /orders");
        context.getLogger("com.example.web.Other").info("unrelated");
        LogchiInstaller.uninstall();
        assertTrue(read("orders-http.pod-1.txt").contains("REQ GET /orders"));
        assertFalse(read("orders-http.pod-1.txt").contains("unrelated"));
        assertFalse(read("orders.pod-1.txt").contains("REQ GET /orders"), "not additive by default");
        assertTrue(read("orders.pod-1.txt").contains("unrelated"));
        assertTrue(context.getLogger("com.example.web.HttpLoggingFilter").isAdditive(), "restored on uninstall");
    }

    @Test
    void aChannelCanAlsoPrintToTheConsole() {
        LogchiSettings s = settings();
        s.getConsole().setEnabled(true);
        LogchiSettings.Sink http = new LogchiSettings.Sink();
        http.setConsole(true);
        http.setLoggers(List.of("com.example.web.HttpLoggingFilter"));
        s.getChannels().put("http", http);
        LogchiInstaller.install(context, s);
        assertNotNull(context.getLogger("com.example.web.HttpLoggingFilter").getAppender("logchi-console"));
        assertNotNull(context.getLogger(Channels.PREFIX + "http").getAppender("logchi-console"));
        assertFalse(context.getLogger("com.example.web.HttpLoggingFilter").isAdditive());
    }

    @Test
    void multiLineLoggersReachTheFileWithTheirLineBreaks() throws IOException {
        LogchiSettings s = settings();
        s.getFile().getAsync().setEnabled(false);
        s.setMultilineLoggers(List.of("com.example.Service"));
        LogchiInstaller.install(context, s);
        logger.info("REQ\nbody");
        LogchiInstaller.uninstall();
        assertTrue(read("orders.pod-1.txt").contains(" : REQ\n\tbody\n"), read("orders.pod-1.txt"));
    }

    @Test
    void filePermissionsAreApplied() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        LogchiSettings s = settings();
        s.setDir(dir.resolve("private").toString());
        s.getFile().getAsync().setEnabled(false);
        s.getFile().setPermissions("640");
        LogchiInstaller.install(context, s);
        logger.info("x");
        LogchiInstaller.uninstall();
        assertEquals("rw-r-----", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(dir.resolve("private/orders.pod-1.txt"))));
        assertEquals("rwxr-x---", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(dir.resolve("private"))));
    }

    @Test
    void badFilePermissionsFailWithAClearMessage() {
        LogchiSettings s = settings();
        s.getFile().setPermissions("999");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LogchiInstaller.install(context, s));
        assertTrue(e.getMessage().contains("999"), e.getMessage());
    }

    /** With files off the channel still owns its console lines, or each would print twice. */
    @Test
    void routedLoggersPrintOnceWhenFilesAreOff() {
        LogchiSettings s = settings();
        s.getFile().setEnabled(false);
        s.getConsole().setEnabled(true);
        LogchiSettings.Sink http = new LogchiSettings.Sink();
        http.setConsole(true);
        http.setLoggers(List.of("com.example.web.HttpLoggingFilter"));
        s.getChannels().put("http", http);
        LogchiInstaller.install(context, s);
        Logger routed = context.getLogger("com.example.web.HttpLoggingFilter");
        assertNotNull(routed.getAppender("logchi-console"));
        assertFalse(routed.isAdditive(), "otherwise the root console prints the line again");
    }

    @Test
    void additivityTheApplicationSetIsPutBack() {
        Logger own = context.getLogger("com.example.web.HttpLoggingFilter");
        own.setAdditive(false);
        LogchiSettings s = settings();
        LogchiSettings.Sink http = new LogchiSettings.Sink();
        http.setAdditive(true);
        http.setLoggers(List.of("com.example.web.HttpLoggingFilter"));
        s.getChannels().put("http", http);
        LogchiInstaller.install(context, s);
        assertTrue(own.isAdditive());
        LogchiInstaller.uninstall();
        assertFalse(own.isAdditive());
    }

    @Test
    void aChannelCannotShareAFileWithALevel() {
        LogchiSettings s = settings();
        s.getChannels().put("error", new LogchiSettings.Sink());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LogchiInstaller.install(context, s));
        assertTrue(e.getMessage().contains("Channel 'error'"), e.getMessage());
    }

    /** YAML reads an unquoted 0640 as the number 416, which must not become a file mode. */
    @Test
    void permissionsThatLockTheOwnerOutAreRejected() {
        for (String bad : List.of("416", "000", "044")) {
            LogchiSettings s = settings();
            s.getFile().setPermissions(bad);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LogchiInstaller.install(context, s), bad);
            assertTrue(e.getMessage().contains("quotes"), e.getMessage());
        }
        LogchiSettings ok = settings();
        ok.getFile().setPermissions("0640");
        LogchiInstaller.install(context, ok);
    }

    /** A reset walks the loggers one by one, so repairing the root logger alone is not enough. */
    @Test
    void routingSurvivesALogbackReset() throws Exception {
        LogchiSettings s = settings();
        LogchiSettings.Sink http = new LogchiSettings.Sink();
        http.setAsync(false);
        http.setLoggers(List.of("com.example.web.HttpLoggingFilter"));
        s.getChannels().put("http", http);
        LogchiInstaller.install(context, s);
        Logger routed = context.getLogger("com.example.web.HttpLoggingFilter");
        routed.info("before");

        // What a reset that overlaps the watchdog leaves behind: the root repaired, this logger undone.
        routed.detachAndStopAllAppenders();
        routed.setAdditive(true);
        await(() -> !routed.isAdditive() && routed.getAppender("logchi-file-orders-http") != null
                && routed.getAppender("logchi-file-orders-http").isStarted());
        routed.info("after");

        context.reset();
        context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        await(() -> !routed.isAdditive() && routed.getAppender("logchi-file-orders-http") != null
                && routed.getAppender("logchi-file-orders-http").isStarted());
        routed.info("after reset");
        LogchiInstaller.uninstall();

        String http1 = read("orders-http.pod-1.txt");
        assertTrue(http1.contains("before") && http1.contains(" : after\n") && http1.contains("after reset"), http1);
        assertFalse(Files.exists(dir.resolve("orders.pod-1.txt")) && read("orders.pod-1.txt").contains("after"));
    }

    @Test
    void anAsyncQueueThatCannotWorkStopsStartup() {
        LogchiSettings s = settings();
        s.getFile().getAsync().setQueueSize(0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LogchiInstaller.install(context, s));
        assertTrue(e.getMessage().contains("queue-size"), e.getMessage());
    }

    @Test
    void dropBelowAcceptsOnlyLevelsThatMayBeDropped() {
        for (String bad : List.of("WARN", "whenever", "")) {
            LogchiSettings s = settings();
            s.getFile().getAsync().setDropBelow(bad);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LogchiInstaller.install(context, s), bad);
            assertTrue(e.getMessage().contains("drop-below"), e.getMessage());
        }
    }

    /** Otherwise every later installation in this process finds its own id taken and adds the pid. */
    @Test
    void aRefusedConfigurationLeavesNoLockBehind() {
        for (java.util.function.Consumer<LogchiSettings> mistake : List.<java.util.function.Consumer<LogchiSettings>>of(
                s -> s.getFile().getRetention().setHot("-5s"),
                s -> s.getFile().getRetention().setCheckInterval("often"),
                s -> s.getFile().getAsync().setFlushTimeout("soon"),
                s -> s.getChannels().put("error", new LogchiSettings.Sink()))) {
            LogchiSettings bad = settings();
            mistake.accept(bad);
            assertThrows(IllegalArgumentException.class, () -> LogchiInstaller.install(context, bad));
        }
        LogchiInstaller.install(context, settings());
        assertTrue(LogchiInstaller.summary().orElseThrow().contains("instance=pod-1 "),
                LogchiInstaller.summary().orElseThrow());
    }

    /** The error file is where people look for errors, whichever logger wrote them. */
    @Test
    void errorsFromAChannelAlsoReachTheErrorFile() throws IOException {
        LogchiSettings s = settings();
        LogchiSettings.Sink http = new LogchiSettings.Sink();
        http.setAsync(false);
        http.setLoggers(List.of("com.example.web.HttpLoggingFilter"));
        s.getChannels().put("http", http);
        LogchiInstaller.install(context, s);
        context.getLogger("com.example.web.HttpLoggingFilter").error("upstream failed");
        context.getLogger("com.example.web.HttpLoggingFilter").info("routine");
        LogchiInstaller.uninstall();
        assertTrue(read("orders-http.pod-1.txt").contains("upstream failed"));
        assertTrue(read("orders-error.pod-1.txt").contains("upstream failed"));
        assertFalse(read("orders-error.pod-1.txt").contains("routine"));
        assertFalse(Files.exists(dir.resolve("orders.pod-1.txt")), "still kept out of the main file");
    }

    @Test
    void anUnusableLockFolderIsReportedOnce() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path locks = Files.createDirectories(dir.resolve(".logchi"));
        Files.setPosixFilePermissions(locks, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true));
        try {
            LogchiInstaller.install(context, settings());
            logger.info("still logging");
            LogchiInstaller.uninstall();
        } finally {
            System.setErr(original);
            Files.setPosixFilePermissions(locks, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        long lines = captured.toString().lines().filter(line -> line.contains("cannot lock instance")).count();
        assertEquals(1, lines, captured.toString());
        assertTrue(files().stream().anyMatch(name -> name.matches("orders\\.pod-1-\\d+\\.txt")), files().toString());
    }

    @Test
    void lockFilesGetTheConfiguredPermissions() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        LogchiSettings s = settings();
        s.getFile().setPermissions("600");
        LogchiInstaller.install(context, s);
        logger.info("x");
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(dir.resolve(".logchi"))));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(dir.resolve(".logchi/orders.pod-1.lock"))));
    }
}
