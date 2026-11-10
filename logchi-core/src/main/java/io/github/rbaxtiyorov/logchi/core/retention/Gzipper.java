package io.github.rbaxtiyorov.logchi.core.retention;

import io.github.rbaxtiyorov.logchi.core.appender.FileNames;
import io.github.rbaxtiyorov.logchi.core.instance.InstanceId;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.zip.GZIPOutputStream;

/**
 * Compresses a file so a reader or a crash can never observe a partial {@code .gz}: data goes to a temp
 * file, is synced to disk, then renamed into place before the source is removed.
 */
final class Gzipper {

    static final String TMP_SUFFIX = ".tmp";

    private final int level;

    Gzipper(int level) {
        this.level = level;
    }

    void compress(Path source) throws IOException {
        Path target = source.resolveSibling(source.getFileName() + FileNames.GZ);
        if (Files.exists(target)) {
            // A previous run finished the rename and died before removing the source.
            Files.deleteIfExists(source);
            return;
        }
        FileTime modified = Files.getLastModifiedTime(source);
        Path tmp = target.resolveSibling(target.getFileName() + "." + InstanceId.pid() + TMP_SUFFIX);
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            OutputStream raw = Channels.newOutputStream(channel);
            try (LevelGzip gzip = new LevelGzip(raw, level)) {
                Files.copy(source, gzip);
                gzip.finish();
                channel.force(true);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        // Keeping the original time means TTLs count from the last write, not from compression.
        Files.setLastModifiedTime(tmp, modified);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(source);
    }

    private static final class LevelGzip extends GZIPOutputStream {
        LevelGzip(OutputStream out, int level) throws IOException {
            super(out, 8192);
            def.setLevel(level);
        }
    }
}
