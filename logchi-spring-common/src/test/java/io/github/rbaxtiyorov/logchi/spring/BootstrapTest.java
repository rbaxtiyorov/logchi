package io.github.rbaxtiyorov.logchi.spring;

import ch.qos.logback.classic.LoggerContext;
import io.github.rbaxtiyorov.logchi.core.Channels;
import io.github.rbaxtiyorov.logchi.core.LogchiInstaller;
import io.github.rbaxtiyorov.logchi.core.Loggers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boots a real application, since installation depends on Spring Boot's startup order. */
class BootstrapTest {

    @TempDir
    Path dir;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class App {
    }

    @AfterEach
    void cleanUp() {
        LogchiInstaller.uninstall();
    }

    private ConfigurableApplicationContext run(String... extra) {
        SpringApplication app = new SpringApplication(App.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        String[] args = Stream.concat(
                Stream.of("--logchi.dir=" + dir, "--logchi.instance-id=t1", "--spring.application.name=svc",
                        "--logchi.file.retention.min-free-disk=0"),
                Stream.of(extra)).toArray(String[]::new);
        return app.run(args);
    }

    private String read(String file) throws IOException {
        return Files.readString(dir.resolve(file));
    }

    private List<String> files() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void capturesStartupLogsAndStillWorksAfterwards() throws IOException {
        Logger log = Loggers.get();
        try (ConfigurableApplicationContext context = run()) {
            log.info("from the application");
        }
        LogchiInstaller.uninstall();
        String content = read("svc.t1.txt");
        assertTrue(content.contains("Starting "), "logs from before beans exist must be captured:\n" + content);
        assertTrue(content.contains("from the application"), content);
        assertTrue(content.contains("logchi: dir="), "startup line:\n" + content);
        assertEquals(BootstrapTest.class.getName(), log.getName());
    }

    @Test
    void bindsEveryKindOfConfiguration() throws IOException {
        try (ConfigurableApplicationContext context = run(
                "--logchi.file.format=json",
                "--logchi.file.json-flavor=compact",
                "--logchi.file.extension=log",
                "--logchi.file.rotation.period=hourly",
                "--logchi.file.rotation.max-size=10MB",
                "--logchi.file.retention.hot=2h",
                "--logchi.file.retention.cold=7d",
                "--logchi.file.retention.scope=own",
                "--logchi.levels.debug.cold=3d",
                "--logchi.channels.audit.cold=365d",
                "--logchi.channels.audit.format=text",
                "--logchi.mask.keys=ssn")) {
            LogchiProperties properties = context.getBean(LogchiProperties.class);
            assertEquals("2h", properties.getFile().getRetention().getHot());
            assertEquals("365d", properties.getChannels().get("audit").getCold());
            assertTrue(properties.getLevels().containsKey("error"), "defaults must survive adding a level");
            assertTrue(properties.getLevels().containsKey("debug"));

            LoggerFactory.getLogger("x").info("ssn=123");
            Channels.get("audit").info("refund");
        }
        LogchiInstaller.uninstall();
        String main = read("svc.t1.log");
        assertTrue(main.lines().allMatch(l -> l.startsWith("{\"ts\":")), main);
        assertTrue(main.contains("ssn=***"), main);
        assertFalse(read("svc-audit.t1.log").startsWith("{"));
    }

    @Test
    void reattachesAfterLogbackResetInARealApplication() throws Exception {
        try (ConfigurableApplicationContext context = run()) {
            assertSurvivesReset("after default reset");
        }
        LogchiInstaller.uninstall();
        assertTrue(read("svc.t1.txt").contains("after default reset"));
    }

    @Test
    void worksAlongsideACustomLogbackConfiguration() throws Exception {
        Path other = dir.resolve("other.log");
        Path config = Files.createTempFile("logback-custom", ".xml");
        Files.writeString(config, """
                <configuration>
                  <appender name="MY_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder><pattern>CUSTOM %%msg%%n</pattern></encoder>
                  </appender>
                  <appender name="MY_FILE" class="ch.qos.logback.core.FileAppender">
                    <file>%s</file>
                    <encoder><pattern>%%msg%%n</pattern></encoder>
                  </appender>
                  <root level="INFO">
                    <appender-ref ref="MY_CONSOLE"/>
                    <appender-ref ref="MY_FILE"/>
                  </root>
                </configuration>
                """.formatted(other));
        try (ConfigurableApplicationContext context = run("--logging.config=" + config.toUri())) {
            ch.qos.logback.classic.Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory())
                    .getLogger(Logger.ROOT_LOGGER_NAME);
            assertNull(root.getAppender("MY_CONSOLE"), "logchi owns the console, or lines would print twice");
            assertNotNull(root.getAppender("MY_FILE"), "the application's other appenders stay");
            assertSurvivesReset("after custom reset");
        } finally {
            Files.deleteIfExists(config);
        }
        LogchiInstaller.uninstall();
        assertTrue(read("svc.t1.txt").contains("after custom reset"));
    }

    private void assertSurvivesReset(String message) throws InterruptedException {
        LoggerContext logback = (LoggerContext) LoggerFactory.getILoggerFactory();
        assertTrue(logback.isStarted(), "the watchdog only heals a started context");
        ch.qos.logback.classic.Logger root = logback.getLogger(Logger.ROOT_LOGGER_NAME);
        logback.reset();
        root.setLevel(ch.qos.logback.classic.Level.INFO);
        long deadline = System.currentTimeMillis() + 10_000;
        while (root.getAppender("logchi-console") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertNotNull(root.getAppender("logchi-console"), "appenders must come back after a reset");
        LoggerFactory.getLogger("x").info(message);
    }

    @Test
    void disabledLeavesNoTrace() throws IOException {
        try (ConfigurableApplicationContext context = run("--logchi.enabled=false")) {
            LoggerFactory.getLogger("x").info("nothing");
            assertTrue(LogchiInstaller.summary().isEmpty());
        }
        assertEquals(List.of(), files());
    }

    @Test
    void invalidConfigurationStopsStartupWithTheReason() {
        Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> run("--logchi.file.retention.cold=whenever"));
        assertTrue(e.getMessage().contains("whenever"), e.toString());
    }
}
