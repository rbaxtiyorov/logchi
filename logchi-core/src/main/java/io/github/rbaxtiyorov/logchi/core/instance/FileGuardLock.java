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
        // A lock file can be deleted by whoever cleans up after a dead instance. A lock taken on the
        // deleted file would guard nothing, because the next process creates a new file of that name.
        for (int attempt = 0; attempt < 5; attempt++) {
            FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                Object opened = identity(file);
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    return null;
                }
                Object locked = identity(file);
                if (opened != null && opened.equals(locked)) {
                    return new FileGuardLock(channel, lock);
                }
                if (opened == null && locked == null && Files.exists(file)) {
                    // This file system has no file identity. The lock is as good as it gets.
                    return new FileGuardLock(channel, lock);
                }
                lock.release();
                channel.close();
            } catch (OverlappingFileLockException e) {
                channel.close();
                return null;
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
        }
        return null;
    }

    private static Object identity(Path file) {
        try {
            return Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        } catch (IOException e) {
            return null;
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
