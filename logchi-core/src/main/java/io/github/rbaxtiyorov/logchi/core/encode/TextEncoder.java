package io.github.rbaxtiyorov.logchi.core.encode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.TargetLengthBasedClassNameAbbreviator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.rbaxtiyorov.logchi.core.mask.Masker;
import org.slf4j.event.KeyValuePair;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** {@code time level [thread] [tid=.. uid=..] logger : message key=value}, one event per line. */
public final class TextEncoder extends LogchiEncoder {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSXXX").withZone(ZoneId.systemDefault());
    private static final int LOGGER_LENGTH = 36;
    private static final int ABBREVIATION_CACHE = 4096;

    private static final String RESET = "\u001b[0m";
    private static final String DIM = "\u001b[2m";
    private static final String CYAN = "\u001b[36m";

    private final TargetLengthBasedClassNameAbbreviator abbreviator =
            new TargetLengthBasedClassNameAbbreviator(LOGGER_LENGTH);
    private final Map<String, String> abbreviated = new ConcurrentHashMap<>();
    private final Map<String, Boolean> multiline = new ConcurrentHashMap<>();
    private final List<String> multilineLoggers;
    private final boolean color;

    public TextEncoder(Masker masker, int stackTraceLines, boolean color) {
        this(masker, stackTraceLines, color, List.of());
    }

    /** Messages of {@code multilineLoggers}, and of loggers below them, keep their line breaks. */
    public TextEncoder(Masker masker, int stackTraceLines, boolean color, List<String> multilineLoggers) {
        super(masker, stackTraceLines);
        this.color = color;
        this.multilineLoggers = List.copyOf(multilineLoggers);
    }

    @Override
    String render(ILoggingEvent e) {
        StringBuilder sb = new StringBuilder(256);
        dim(sb, TIME.format(Instant.ofEpochMilli(e.getTimeStamp())));
        sb.append(' ');
        appendLevel(sb, e.getLevel());
        sb.append(' ');
        dim(sb, "[" + oneLine(e.getThreadName()) + "]");
        sb.append(' ');
        appendContext(sb, e.getMDCPropertyMap());
        appendLogger(sb, e.getLoggerName());
        sb.append(" : ").append(maskedMessage(e, keepsLines(e.getLoggerName()) ? INDENTED : ONE_LINE));
        appendKeyValues(sb, e.getKeyValuePairs());
        sb.append('\n');
        if (e.getThrowableProxy() != null) {
            sb.append(masker.mask(ThrowableRenderer.render(e.getThrowableProxy(), stackTraceLines)));
        }
        return sb.toString();
    }

    @Override
    String fallback(ILoggingEvent e, Throwable failure) {
        return TIME.format(Instant.ofEpochMilli(e.getTimeStamp())) + ' ' + String.format("%-5s", e.getLevel())
                + " [" + oneLine(e.getThreadName()) + "] " + e.getLoggerName() + " : [logchi could not render this event: "
                + failure.getClass().getSimpleName() + "]\n";
    }

    private void appendLevel(StringBuilder sb, Level level) {
        String text = String.format("%-5s", level);
        if (color) {
            sb.append(levelColor(level)).append(text).append(RESET);
        } else {
            sb.append(text);
        }
    }

    private static String levelColor(Level level) {
        return switch (level.toInt()) {
            case Level.ERROR_INT -> "\u001b[31m";
            case Level.WARN_INT -> "\u001b[33m";
            case Level.INFO_INT -> "\u001b[32m";
            default -> "\u001b[34m";
        };
    }

    private void dim(StringBuilder sb, String text) {
        if (color) {
            sb.append(DIM).append(text).append(RESET);
        } else {
            sb.append(text);
        }
    }

    private void appendContext(StringBuilder sb, Map<String, String> mdc) {
        if (mdc == null || mdc.isEmpty()) {
            return;
        }
        Map<String, String> rest = new TreeMap<>(mdc);
        StringBuilder ctx = new StringBuilder("[");
        appendPair(ctx, "tid", "traceId", rest.remove("traceId"));
        appendPair(ctx, "uid", "userId", rest.remove("userId"));
        rest.forEach((k, v) -> appendPair(ctx, k, k, v));
        ctx.append("] ");
        dim(sb, ctx.toString());
    }

    private void appendPair(StringBuilder ctx, String label, String key, String value) {
        if (value == null) {
            return;
        }
        if (ctx.length() > 1) {
            ctx.append(' ');
        }
        ctx.append(label).append('=').append(quoted(maskField(key, oneLine(value))));
    }

    private void appendLogger(StringBuilder sb, String name) {
        String shortName = abbreviated.get(name);
        if (shortName == null) {
            shortName = abbreviator.abbreviate(name);
            if (abbreviated.size() < ABBREVIATION_CACHE) {
                abbreviated.put(name, shortName);
            }
        }
        if (color) {
            sb.append(CYAN).append(shortName).append(RESET);
        } else {
            sb.append(shortName);
        }
    }

    private void appendKeyValues(StringBuilder sb, List<KeyValuePair> pairs) {
        if (pairs == null) {
            return;
        }
        for (KeyValuePair pair : pairs) {
            sb.append(' ').append(oneLine(pair.key)).append('=')
                    .append(quoted(maskField(pair.key, oneLine(String.valueOf(pair.value)))));
        }
    }

    private boolean keepsLines(String logger) {
        if (multilineLoggers.isEmpty()) {
            return false;
        }
        Boolean known = multiline.get(logger);
        if (known == null) {
            known = multilineLoggers.stream().anyMatch(p -> logger.equals(p) || logger.startsWith(p + "."));
            if (multiline.size() < ABBREVIATION_CACHE) {
                multiline.put(logger, known);
            }
        }
        return known;
    }

    private static String quoted(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return '"' + value + '"';
            }
        }
        return value;
    }
}
