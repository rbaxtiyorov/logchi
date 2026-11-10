package io.github.rbaxtiyorov.logchi.core.appender;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.concurrent.atomic.AtomicLong;

/** When the queue is nearly full, drops events at or below a level and counts them. WARN and ERROR block instead. */
public final class LevelDropAsyncAppender extends AsyncAppender {

    private final int dropAtOrBelow;
    private final AtomicLong dropped = new AtomicLong();

    public LevelDropAsyncAppender(Level dropAtOrBelow) {
        // Capped at INFO: WARN and ERROR are never discarded, whatever the configuration says.
        this.dropAtOrBelow = Math.min(dropAtOrBelow.toInt(), Level.INFO_INT);
    }

    @Override
    protected boolean isDiscardable(ILoggingEvent event) {
        if (event.getLevel().toInt() <= dropAtOrBelow) {
            dropped.incrementAndGet();
            return true;
        }
        return false;
    }

    /** Returns and resets the number of events dropped since the last call. */
    public long drainDropped() {
        return dropped.getAndSet(0);
    }
}
