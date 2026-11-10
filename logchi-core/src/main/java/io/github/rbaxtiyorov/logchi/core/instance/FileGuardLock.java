package io.github.rbaxtiyorov.logchi.core.instance;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** An exclusive OS file lock. Released automatically if the process dies. */
public final class FileGuardLock implements Closeable {

    private final FileChannel channel;
    private final FileLock lock;

    private FileGuardLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Returns the lock, or null if another process or this JVM already holds it. */
    public static FileGuardLock tryAcquire(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            return new FileGuardLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            channel.close();
            return null;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
