package io.github.rbaxtiyorov.logchi.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the human-friendly values used in configuration: {@code 500ms}, {@code 30d}, {@code 100MB}. */
public final class Parse {

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)?");
    private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*(b|kb|mb|gb|tb)?");

    private Parse() {
    }

    /** Returns milliseconds. A bare number is taken as milliseconds. */
    public static long durationMillis(String value) {
        Matcher m = DURATION.matcher(normalize(value));
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid duration '" + value + "'. Examples: 500ms, 2s, 5m, 12h, 30d");
        }
        long amount = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "ms" : m.group(2);
        return switch (unit) {
            case "ms" -> amount;
            case "s" -> amount * 1_000L;
            case "m" -> amount * 60_000L;
            case "h" -> amount * 3_600_000L;
            case "d" -> amount * 86_400_000L;
            default -> throw new IllegalStateException(unit);
        };
    }

    /** Returns bytes. A bare number is taken as bytes. */
    public static long bytes(String value) {
        Matcher m = SIZE.matcher(normalize(value));
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid size '" + value + "'. Examples: 512KB, 100MB, 5GB");
        }
        long amount = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "b" : m.group(2);
        return switch (unit) {
            case "b" -> amount;
            case "kb" -> amount << 10;
            case "mb" -> amount << 20;
            case "gb" -> amount << 30;
            case "tb" -> amount << 40;
            default -> throw new IllegalStateException(unit);
        };
    }

    /** Renders a byte count for humans, e.g. {@code 1.2GB}. */
    public static String humanBytes(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0 ? bytes + "B" : String.format(Locale.ROOT, "%.1f%s", value, units[unit]);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
