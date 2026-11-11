package io.github.rbaxtiyorov.logchi.core.appender;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

/** Optional permissions for log files, given as {@code 640} or {@code rw-r-----}. Does nothing off POSIX. */
public final class FileModes {

    public static final FileModes DEFAULT = new FileModes(null);

    private final Set<PosixFilePermission> file;
    private final Set<PosixFilePermission> directory;
    private volatile boolean reported;

    private FileModes(Set<PosixFilePermission> file) {
        this.file = file;
        this.directory = file == null ? null : withSearch(file);
    }

    /** A blank value leaves permissions to the process umask. */
    public static FileModes parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String text = value.trim();
        try {
            if (text.matches("0?[0-7]{3}")) {
                StringBuilder symbolic = new StringBuilder(9);
                for (char c : text.substring(text.length() - 3).toCharArray()) {
                    int bits = c - '0';
                    symbolic.append((bits & 4) != 0 ? 'r' : '-').append((bits & 2) != 0 ? 'w' : '-')
                            .append((bits & 1) != 0 ? 'x' : '-');
                }
                text = symbolic.toString();
            }
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(text);
            if (!permissions.contains(PosixFilePermission.OWNER_READ)
                    || !permissions.contains(PosixFilePermission.OWNER_WRITE)) {
                throw new IllegalArgumentException("the owner must be able to read and write");
            }
            return new FileModes(permissions);
        } catch (IllegalArgumentException e) {
            // In YAML an unquoted 0640 is read as the number 416, which is why quoting is suggested.
            throw new IllegalArgumentException("Invalid file permissions '" + value
                    + "'. Examples: \"640\", \"600\", rw-r-----. In YAML, put the number in quotes");
        }
    }

    /** A directory needs search permission wherever it grants read. */
    private static Set<PosixFilePermission> withSearch(Set<PosixFilePermission> file) {
        Set<PosixFilePermission> dir = EnumSet.copyOf(file);
        if (file.contains(PosixFilePermission.OWNER_READ)) {
            dir.add(PosixFilePermission.OWNER_EXECUTE);
        }
        if (file.contains(PosixFilePermission.GROUP_READ)) {
            dir.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if (file.contains(PosixFilePermission.OTHERS_READ)) {
            dir.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        return dir;
    }

    public void applyToFile(Path path) {
        apply(path, file);
    }

    public void applyToDirectory(Path path) {
        apply(path, directory);
    }

    private void apply(Path path, Set<PosixFilePermission> permissions) {
        if (permissions == null) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException e) {
            // Not a POSIX file system, or not ours to change. Writing the log matters more, but someone
            // who asked for private files should know they did not get them.
            if (!reported) {
                reported = true;
                System.err.println("logchi: could not set permissions on " + path + ": " + e);
            }
        }
    }
}
