package io.github.rbaxtiyorov.logchi.core;

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real separate JVMs configured with the same instance id, all writing at the same time. */
class MultiJvmTest {

    private static final int JVMS = 3;
    private static final int LINES = 20_000;

    @TempDir
    Path tmp;

    @Test
    void jvmsWithTheSameIdNeverShareAFile() throws Exception {
        Path logs = tmp.resolve("logs");
        Path control = Files.createDirectories(tmp.resolve("control"));
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";

        List<Process> processes = new ArrayList<>();
        for (int i = 0; i < JVMS; i++) {
            processes.add(new ProcessBuilder(java, "-cp", classpath, Child.class.getName(), logs.toString(),
                    control.toString(), String.valueOf(i))
                    .redirectErrorStream(true)
                    .redirectOutput(tmp.resolve("child-" + i + ".out").toFile())
                    .start());
        }

        long deadline = System.currentTimeMillis() + 60_000;
        while (fileCount(control) < JVMS && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(JVMS, fileCount(control), "every JVM must be installed before writing starts");
        Files.writeString(control.resolve("go"), "");

        for (int i = 0; i < JVMS; i++) {
            Process process = processes.get(i);
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "child timed out");
            assertEquals(0, process.exitValue(), Files.readString(tmp.resolve("child-" + i + ".out")));
        }

        List<Path> files;
        try (Stream<Path> s = Files.list(logs)) {
            files = s.filter(Files::isRegularFile).toList();
        }
        assertEquals(JVMS, files.size(), files.toString());

        int total = 0;
        for (Path file : files) {
            String owner = null;
            for (String line : Files.readAllLines(file)) {
                assertTrue(line.matches(".* : child-\\d line \\d+ end$"), "corrupt line: " + line);
                String child = line.substring(line.indexOf("child-"), line.indexOf(" line "));
                if (owner == null) {
                    owner = child;
                }
                assertEquals(owner, child, "two JVMs wrote to " + file.getFileName());
                total++;
            }
        }
        assertEquals(JVMS * LINES, total);
    }

    private static long fileCount(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.count();
        }
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            Path control = Path.of(args[1]);
            String id = args[2];

            LogchiSettings settings = new LogchiSettings();
            settings.setDir(args[0]);
            settings.setAppName("app");
            settings.setInstanceId("same-id");
            settings.getConsole().setEnabled(false);
            settings.getFile().getRetention().setMinFreeDisk("0");
            settings.getFile().getAsync().setQueueSize(256);
            settings.getLevels().clear();

            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            LogchiInstaller.install(context, settings);

            Files.writeString(control.resolve("ready-" + id), "");
            while (!Files.exists(control.resolve("go"))) {
                Thread.sleep(10);
            }
            Logger logger = context.getLogger("child");
            for (int i = 0; i < LINES; i++) {
                // WARN is never dropped, so an exact line count proves no loss.
                logger.warn("child-{} line {} end", id, i);
            }
            context.stop();
        }
    }
}
