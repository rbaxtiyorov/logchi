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
}
