package io.github.rbaxtiyorov.logchi.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.filter.LevelFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.spi.FilterReply;
import io.github.rbaxtiyorov.logchi.core.LogchiSettings.Format;
import io.github.rbaxtiyorov.logchi.core.appender.FileModes;
import io.github.rbaxtiyorov.logchi.core.appender.FileNames;
import io.github.rbaxtiyorov.logchi.core.appender.LevelDropAsyncAppender;
import io.github.rbaxtiyorov.logchi.core.appender.LogchiFileAppender;
import io.github.rbaxtiyorov.logchi.core.encode.JsonEncoder;
import io.github.rbaxtiyorov.logchi.core.encode.TextEncoder;
import io.github.rbaxtiyorov.logchi.core.instance.FileGuardLock;
import io.github.rbaxtiyorov.logchi.core.instance.InstanceId;
import io.github.rbaxtiyorov.logchi.core.mask.Masker;
import io.github.rbaxtiyorov.logchi.core.retention.DiskGuard;
import io.github.rbaxtiyorov.logchi.core.retention.Janitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Attaches logchi to a Logback context. Installing again replaces the previous installation.
 *
 * <p>Works without Spring. Plain Logback users should call {@code loggerContext.stop()} on exit so
 * queued events are flushed; Spring Boot does that itself.
 */
public final class LogchiInstaller {

    private static Installation current;

    private LogchiInstaller() {
    }

    public static synchronized void install(LoggerContext context, LogchiSettings settings) {
        uninstall();
        if (settings.isEnabled()) {
            Installation installation = new Installation(context, settings);
            try {
                installation.begin();
            } catch (RuntimeException e) {
                installation.end();
                throw e;
            }
            current = installation;
        }
    }

    public static synchronized void uninstall() {
        if (current != null) {
            current.end();
            current = null;
        }
    }

    /** One-line description of the active installation, if there is one. */
    public static synchronized Optional<String> summary() {
        return Optional.ofNullable(current).map(Installation::summary);
    }

    private static final class Installation {

        private static final Logger log = LoggerFactory.getLogger("logchi");
        private static final String CONSOLE = "logchi-console";
        /** How often the watchdog looks. Short, because lines logged while something is detached go astray. */
        private static final long WATCH_MILLIS = 250;
        private static final int TICKS_PER_SECOND = (int) (1_000 / WATCH_MILLIS);

        private final LoggerContext context;
        private final LogchiSettings settings;
        private final Path dir;
        private final String appName;
        private final String instance;
        private final FileGuardLock instanceLock;
        private final FileNames names;
        private final FileModes modes;
        private final Masker masker;
        private final DiskGuard guard;
        private final Janitor janitor;
        private final ScheduledExecutorService scheduler;

        private final List<Attachment> attachments = new ArrayList<>();
        private final List<LevelDropAsyncAppender> asyncAppenders = new ArrayList<>();
        private final List<LogchiFileAppender> fileAppenders = new ArrayList<>();
        /** Loggers whose additivity logchi changed, with the value to put back. */
        private final Map<ch.qos.logback.classic.Logger, Boolean> routed = new LinkedHashMap<>();
        private boolean lockFailed;
        /** Additivity logchi set, which a Logback reset silently undoes. */
        private final Map<ch.qos.logback.classic.Logger, Boolean> expectedAdditive = new LinkedHashMap<>();
        private int ticks;

        private record Attachment(ch.qos.logback.classic.Logger logger, Appender<ILoggingEvent> appender) {
        }

        Installation(LoggerContext context, LogchiSettings settings) {
            this.context = context;
            this.settings = settings;
            this.dir = Path.of(settings.getDir()).toAbsolutePath().normalize();
            String name = settings.getAppName();
            this.appName = InstanceId.sanitize(name == null || name.isBlank() ? "app" : name);
            this.names = new FileNames(InstanceId.sanitize(settings.getFile().getExtension()));
            this.modes = FileModes.parse(settings.getFile().getPermissions());
            this.masker = Masker.from(settings.getMask());

            LogchiSettings.Retention retention = settings.getFile().getRetention();
            if (retention.getCompressLevel() < 1 || retention.getCompressLevel() > 9) {
                throw new IllegalArgumentException("Invalid compress-level '" + retention.getCompressLevel()
                        + "'. Use 1 (fastest) to 9 (smallest)");
            }
            this.guard = new DiskGuard(dir, Parse.bytes(retention.getMinFreeDisk()));
            // Everything that can be wrong is read before the instance lock is taken, so a refused
            // configuration never leaves a lock behind.
            Map<String, Janitor.Policy> policies = policies();
            long maxTotal = Parse.bytes(retention.getMaxTotal());
            Parse.durationMillis(retention.getCheckInterval());
            LogchiSettings.Async async = settings.getFile().getAsync();
            Parse.durationMillis(async.getFlushTimeout());
            if (async.getQueueSize() < 16) {
                throw new IllegalArgumentException("Invalid queue-size '" + async.getQueueSize() + "'. Use 16 or more");
            }
            String dropBelow = async.getDropBelow() == null ? "" : async.getDropBelow().trim().toUpperCase(Locale.ROOT);
            if (!List.of("TRACE", "DEBUG", "INFO").contains(dropBelow)) {
                throw new IllegalArgumentException("Invalid drop-below '" + async.getDropBelow()
                        + "'. Use TRACE, DEBUG or INFO. WARN and ERROR are never dropped");
            }
            levelSinks().keySet().forEach(level -> {
                if (Level.toLevel(level.toUpperCase(Locale.ROOT), null) == null) {
                    throw new IllegalArgumentException("Unknown level in logchi.levels: " + level);
                }
            });

            String id = InstanceId.resolve(settings.getInstanceId());
            // Without files there is nothing to guard, and the folder is left untouched.
            boolean fresh = !Files.isDirectory(dir);
            FileGuardLock lock = settings.getFile().isEnabled() ? lockInstance(id) : null;
            if (fresh && Files.isDirectory(dir)) {
                modes.applyToDirectory(dir);
            }
            if (Files.isDirectory(dir.resolve(".logchi"))) {
                modes.applyToDirectory(dir.resolve(".logchi"));
            }
            if (lock == null && settings.getFile().isEnabled()) {
                // Same id already live, e.g. two JVMs on one host. The pid makes this one distinct.
                id = id + "-" + InstanceId.pid();
                lock = lockInstance(id);
            }
            this.instance = id;
            this.instanceLock = lock;

            this.janitor = new Janitor(dir, appName, instance, names, policies, retention.isCompress(),
                    retention.getCompressLevel(), maxTotal, retention.getScope(), modes);
            this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "logchi-housekeeper");
                thread.setDaemon(true);
                return thread;
            });
        }

        private FileGuardLock lockInstance(String id) {
            try {
                Path file = dir.resolve(".logchi").resolve(appName + "." + id + ".lock");
                FileGuardLock lock = FileGuardLock.tryAcquire(file);
                if (lock != null) {
                    modes.applyToFile(file);
                }
                return lock;
            } catch (IOException e) {
                // It must not stop startup. But without a lock nothing proves the id is free, and two
                // processes writing one file corrupt it, so this is treated like an id that is taken.
                if (!lockFailed) {
                    lockFailed = true;
                    System.err.println("logchi: cannot lock instance '" + id + "' in " + dir.resolve(".logchi")
                            + ", adding the process id to file names: " + e);
                }
                return null;
            }
        }

        private Map<String, Janitor.Policy> policies() {
            LogchiSettings.Retention retention = settings.getFile().getRetention();
            Map<String, Janitor.Policy> policies = new LinkedHashMap<>();
            policies.put(appName, new Janitor.Policy(Parse.durationMillis(retention.getHot()),
                    Parse.durationMillis(retention.getCold())));
            levelSinks().forEach((level, sink) -> policies.put(levelStem(level), policy(sink)));
            channelSinks().forEach((channel, sink) -> {
                if (policies.containsKey(channelStem(channel))) {
                    throw new IllegalArgumentException("Channel '" + channel
                            + "' would share a file with the level of that name. Rename the channel");
                }
                policies.put(channelStem(channel), policy(sink));
            });
            return policies;
        }

        private Janitor.Policy policy(LogchiSettings.Sink sink) {
            LogchiSettings.Retention retention = settings.getFile().getRetention();
            return new Janitor.Policy(
                    Parse.durationMillis(sink.getHot() != null ? sink.getHot() : retention.getHot()),
                    Parse.durationMillis(sink.getCold() != null ? sink.getCold() : retention.getCold()));
        }

        private Map<String, LogchiSettings.Sink> levelSinks() {
            return enabledSinks(settings.getLevels());
        }

        private Map<String, LogchiSettings.Sink> channelSinks() {
            return enabledSinks(settings.getChannels());
        }

        private static Map<String, LogchiSettings.Sink> enabledSinks(Map<String, LogchiSettings.Sink> sinks) {
            Map<String, LogchiSettings.Sink> enabled = new LinkedHashMap<>();
            sinks.forEach((name, sink) -> {
                if (sink.isEnabled()) {
                    enabled.put(name, sink);
                }
            });
            return enabled;
        }

        private String levelStem(String level) {
            return appName + "-" + InstanceId.sanitize(level.toLowerCase(Locale.ROOT));
        }

        private String channelStem(String channel) {
            return appName + "-" + InstanceId.sanitize(channel);
        }

        void begin() {
            guard.check();
            attach();
            long interval = Parse.durationMillis(settings.getFile().getRetention().getCheckInterval());
            scheduler.scheduleWithFixedDelay(this::watch, WATCH_MILLIS, WATCH_MILLIS, TimeUnit.MILLISECONDS);
            if (settings.getFile().isEnabled()) {
                scheduler.scheduleWithFixedDelay(janitor::runSafely, 5_000, Math.max(interval, 1_000),
                        TimeUnit.MILLISECONDS);
            }
        }

        void end() {
            scheduler.shutdownNow();
            try {
                // A retention pass may be in the middle of a file. Let it finish before the caller moves on.
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            detach();
            if (instanceLock != null) {
                try {
                    instanceLock.close();
                } catch (IOException ignored) {
                    // The OS releases the lock when the process exits.
                }
            }
        }

        /** Runs every second. Logback resets, as Spring Cloud performs, silently remove appenders. */
        private void watch() {
            try {
                synchronized (LogchiInstaller.class) {
                    if (context.isStarted() && !attachments.isEmpty() && !intact()) {
                        repair();
                    } else if (!context.isStarted() && !attachments.isEmpty()) {
                        // Logback was shut down for good. A repair made while it was stopping must not linger.
                        detach();
                    }
                }
                ticks++;
                if (ticks % (5 * TICKS_PER_SECOND) == 0 && settings.getFile().isEnabled() && guard.check()) {
                    scheduler.execute(janitor::runSafely);
                }
                if (ticks % (60 * TICKS_PER_SECOND) == 0) {
                    synchronized (LogchiInstaller.class) {
                        reportLosses();
                    }
                }
            } catch (Exception e) {
                log.warn("logchi: watchdog failed: {}", e.toString());
            }
        }

        /**
         * Every appender still attached and every routed logger still routed. Checking one of them is not
         * enough: a reset walks the loggers one by one and can undo a logger after another was repaired.
         */
        private boolean intact() {
            for (Attachment attachment : attachments) {
                // A reset stops an appender seconds before it removes it from its logger. A stopped
                // appender drops every event, so it counts as gone already.
                if (!attachment.logger().isAttached(attachment.appender()) || !attachment.appender().isStarted()) {
                    return false;
                }
            }
            for (Map.Entry<ch.qos.logback.classic.Logger, Boolean> entry : expectedAdditive.entrySet()) {
                if (entry.getKey().isAdditive() != entry.getValue()) {
                    return false;
                }
            }
            return true;
        }

        private void reportLosses() {
            long dropped = asyncAppenders.stream().mapToLong(LevelDropAsyncAppender::drainDropped).sum();
            long lost = fileAppenders.stream().mapToLong(LogchiFileAppender::drainLost).sum();
            if (dropped > 0) {
                log.warn("logchi: dropped {} low-level events because the log queue was full", dropped);
            }
            if (lost > 0) {
                log.warn("logchi: {} events could not be written to disk", lost);
            }
        }

        private void attach() {
            ch.qos.logback.classic.Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            // Decided here and not with the files: a channel also keeps its lines out of the root console.
            channelSinks().forEach((channel, sink) -> {
                for (ch.qos.logback.classic.Logger logger : channelLoggers(channel, sink)) {
                    routed.putIfAbsent(logger, logger.isAdditive());
                    logger.setAdditive(sink.isAdditive());
                    expectedAdditive.put(logger, sink.isAdditive());
                }
            });
            // Files first: once the console is back after a reset, every line must reach the files as well.
            if (settings.getFile().isEnabled()) {
                attachFiles(root);
            }
            attachConsole(root);
        }

        private void attachConsole(ch.qos.logback.classic.Logger root) {
            List<Appender<ILoggingEvent>> existing = new ArrayList<>();
            for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
                Appender<ILoggingEvent> appender = it.next();
                if (appender instanceof ConsoleAppender) {
                    existing.add(appender);
                }
            }
            LogchiSettings.Console console = settings.getConsole();
            if (console.isEnabled()) {
                ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
                appender.setContext(context);
                appender.setName(CONSOLE);
                appender.setEncoder(encoder(console.getFormat(), useColor(console)));
                appender.start();
                root.addAppender(appender);
                record(root, appender);
                channelSinks().forEach((channel, sink) -> {
                    if (sink.isConsole() && !sink.isAdditive()) {
                        for (ch.qos.logback.classic.Logger logger : channelLoggers(channel, sink)) {
                            logger.addAppender(appender);
                            record(logger, appender);
                        }
                    }
                });
            }
            // Swapped after ours is live so no line falls between the two.
            for (Appender<ILoggingEvent> appender : existing) {
                root.detachAppender(appender);
                appender.stop();
            }
        }

        private void attachFiles(ch.qos.logback.classic.Logger root) {
            LogchiSettings.File file = settings.getFile();
            attachSink(root, appName, file.getFormat(), file.getAsync().isEnabled(), null);
            levelSinks().forEach((level, sink) -> {
                Level parsed = Level.toLevel(level.toUpperCase(Locale.ROOT), null);
                if (parsed == null) {
                    throw new IllegalArgumentException("Unknown level in logchi.levels: " + level);
                }
                Appender<ILoggingEvent> appender = attachSink(root, levelStem(level), formatOf(sink), asyncOf(sink),
                        parsed);
                // A channel that keeps out of the main log does not reach the root logger. Its errors
                // still belong in the error file.
                channelSinks().forEach((channel, channelSink) -> {
                    if (!channelSink.isAdditive()) {
                        for (ch.qos.logback.classic.Logger logger : channelLoggers(channel, channelSink)) {
                            logger.addAppender(appender);
                            record(logger, appender);
                        }
                    }
                });
            });
            channelSinks().forEach((channel, sink) -> {
                List<ch.qos.logback.classic.Logger> loggers = channelLoggers(channel, sink);
                Appender<ILoggingEvent> appender = attachSink(loggers.get(0), channelStem(channel), formatOf(sink),
                        asyncOf(sink), null);
                for (ch.qos.logback.classic.Logger logger : loggers) {
                    if (logger != loggers.get(0)) {
                        logger.addAppender(appender);
                        record(logger, appender);
                    }
                }
            });
        }

        /** The channel's own logger, then every existing logger routed to it by configuration. */
        private List<ch.qos.logback.classic.Logger> channelLoggers(String channel, LogchiSettings.Sink sink) {
            List<ch.qos.logback.classic.Logger> loggers = new ArrayList<>();
            loggers.add(context.getLogger(Channels.PREFIX + channel));
            for (String name : sink.getLoggers()) {
                if (name != null && !name.isBlank()) {
                    loggers.add(context.getLogger(name.trim()));
                }
            }
            return loggers;
        }

        private Format formatOf(LogchiSettings.Sink sink) {
            return sink.getFormat() != null ? sink.getFormat() : settings.getFile().getFormat();
        }

        private boolean asyncOf(LogchiSettings.Sink sink) {
            return sink.getAsync() != null ? sink.getAsync() : settings.getFile().getAsync().isEnabled();
        }

        private Appender<ILoggingEvent> attachSink(ch.qos.logback.classic.Logger logger, String stem, Format format,
                                                   boolean async, Level onlyLevel) {
            LogchiSettings.File file = settings.getFile();
            LogchiFileAppender fileAppender = new LogchiFileAppender(dir, names, stem, instance,
                    file.getRotation().getPeriod(), Parse.bytes(file.getRotation().getMaxSize()),
                    encoder(format, false), guard, modes);
            fileAppender.setContext(context);
            fileAppender.setName("logchi-file-" + stem);
            fileAppender.start();
            fileAppenders.add(fileAppender);

            Appender<ILoggingEvent> attached = async ? wrapAsync(fileAppender, stem) : fileAppender;
            if (onlyLevel != null) {
                LevelFilter filter = new LevelFilter();
                filter.setContext(context);
                filter.setLevel(onlyLevel);
                filter.setOnMatch(FilterReply.ACCEPT);
                filter.setOnMismatch(FilterReply.DENY);
                filter.start();
                attached.addFilter(filter);
            }
            logger.addAppender(attached);
            record(logger, attached);
            return attached;
        }

        private Appender<ILoggingEvent> wrapAsync(Appender<ILoggingEvent> target, String stem) {
            LogchiSettings.Async options = settings.getFile().getAsync();
            LevelDropAsyncAppender async = new LevelDropAsyncAppender(Level.toLevel(options.getDropBelow(), Level.INFO));
            async.setContext(context);
            async.setName("logchi-async-" + stem);
            async.setQueueSize(options.getQueueSize());
            async.setDiscardingThreshold(options.getQueueSize() / 5);
            async.setMaxFlushTime((int) Parse.durationMillis(options.getFlushTimeout()));
            async.addAppender(target);
            async.start();
            asyncAppenders.add(async);
            return async;
        }

        private void record(ch.qos.logback.classic.Logger logger, Appender<ILoggingEvent> appender) {
            Attachment attachment = new Attachment(logger, appender);
            attachments.add(attachment);
        }

        /**
         * Puts fresh appenders in place first and stops the old ones afterwards, off this thread. Stopping
         * flushes queues, which can take seconds under load, and until the new appenders are attached every
         * line goes to the wrong place or nowhere.
         */
        private void repair() {
            List<Attachment> old = new ArrayList<>(attachments);
            old.forEach(attachment -> attachment.logger().detachAppender(attachment.appender()));
            forget();
            attach();
            scheduler.execute(() -> old.forEach(attachment -> attachment.appender().stop()));
        }

        private void detach() {
            for (Attachment attachment : attachments) {
                attachment.logger().detachAppender(attachment.appender());
                attachment.appender().stop();
            }
            forget();
        }

        private void forget() {
            attachments.clear();
            routed.forEach(ch.qos.logback.classic.Logger::setAdditive);
            routed.clear();
            asyncAppenders.clear();
            fileAppenders.clear();
            expectedAdditive.clear();
        }

        private Encoder<ILoggingEvent> encoder(Format format, boolean color) {
            Encoder<ILoggingEvent> encoder = format == Format.JSON
                    ? new JsonEncoder(settings.getFile().getJsonFlavor(), masker, settings.getStackTraceLines(), appName, instance)
                    : new TextEncoder(masker, settings.getStackTraceLines(), color, settings.getMultilineLoggers());
            encoder.setContext(context);
            encoder.start();
            return encoder;
        }

        private static boolean useColor(LogchiSettings.Console console) {
            return switch (console.getColor()) {
                case ALWAYS -> true;
                case NEVER -> false;
                case AUTO -> isInteractiveTerminal();
            };
        }

        private static boolean isInteractiveTerminal() {
            if (System.getenv("NO_COLOR") != null || "dumb".equals(System.getenv("TERM"))) {
                return false;
            }
            Console console = System.console();
            if (console == null) {
                return false;
            }
            try {
                // Since JDK 22 System.console() is non-null even when output is redirected.
                Method isTerminal = Console.class.getMethod("isTerminal");
                return (boolean) isTerminal.invoke(console);
            } catch (ReflectiveOperationException e) {
                return true;
            }
        }

        String summary() {
            LogchiSettings.Retention r = settings.getFile().getRetention();
            long used = janitor.usedBytes();
            long cap = Parse.bytes(r.getMaxTotal());
            String percent = cap > 0 ? "(" + (used * 100 / cap) + "%)" : "";
            return "logchi: dir=" + dir + " instance=" + instance
                    + " format=" + settings.getFile().getFormat().name().toLowerCase(Locale.ROOT)
                    + " hot=" + r.getHot() + " cold=" + r.getCold() + " cap=" + r.getMaxTotal()
                    + " used=" + Parse.humanBytes(used) + percent
                    + " channels=" + channelSinks().keySet() + " levels=" + levelSinks().keySet();
        }
    }
}
