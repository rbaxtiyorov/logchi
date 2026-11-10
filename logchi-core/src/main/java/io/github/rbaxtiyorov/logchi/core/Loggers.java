package io.github.rbaxtiyorov.logchi.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@code private static final Logger log = Loggers.get();} works in any class, Spring bean or not. */
public final class Loggers {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private Loggers() {
    }

    /** Returns a standard SLF4J logger named after the calling class. */
    public static Logger get() {
        return LoggerFactory.getLogger(WALKER.getCallerClass());
    }
}
