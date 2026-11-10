package io.github.rbaxtiyorov.logchi.core.appender;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File name scheme shared by the appender and the janitor.
 *
 * <pre>
 * orders.api-7d9f.txt                       active
 * orders.api-7d9f.2026-11-08.0.txt          rolled, hot
 * orders.api-7d9f.2026-11-08.0.txt.gz       rolled, cold
 * orders-audit.api-7d9f.txt                 active file of a sink
 * </pre>
 *
 * Stems and instance ids are sanitized to exclude dots, so splitting on dots is unambiguous.
 */
public final class FileNames {

    public static final String GZ = ".gz";

    private final Pattern pattern;
    private final String extension;

    public FileNames(String extension) {
        this.extension = extension;
        this.pattern = Pattern.compile("^([A-Za-z0-9_-]+)\\.([A-Za-z0-9_-]+)(?:\\.(\\d{4}-\\d{2}-\\d{2}(?:-\\d{2})?)\\.(\\d+))?\\."
                + Pattern.quote(extension) + "(\\.gz)?$");
    }

    public String active(String stem, String instance) {
        return stem + "." + instance + "." + extension;
    }

    public String rolled(String stem, String instance, String periodKey, int index) {
        return stem + "." + instance + "." + periodKey + "." + index + "." + extension;
    }

    public Optional<Parsed> parse(String fileName) {
        Matcher m = pattern.matcher(fileName);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(m.group(1), m.group(2), m.group(3), m.group(5) != null));
    }

    /** {@code periodKey} is null for an active file. */
    public record Parsed(String stem, String instance, String periodKey, boolean gzipped) {
        public boolean isActive() {
            return periodKey == null;
        }
    }
}
