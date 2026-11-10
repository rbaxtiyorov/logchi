package io.github.rbaxtiyorov.logchi.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Named log streams with their own file and retention, declared under {@code logchi.channels}. */
public final class Channels {

    public static final String PREFIX = "logchi.channel.";

    private Channels() {
    }

    /** Returns the logger for a channel. An undeclared channel falls through to the main log. */
    public static Logger get(String name) {
        return LoggerFactory.getLogger(PREFIX + name);
    }
}
