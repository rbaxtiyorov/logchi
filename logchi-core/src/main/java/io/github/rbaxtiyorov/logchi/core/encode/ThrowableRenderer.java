package io.github.rbaxtiyorov.logchi.core.encode;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Renders stack traces, limiting frames per throwable so {@code Caused by} sections always survive. */
final class ThrowableRenderer {

    private ThrowableRenderer() {
    }

    static String render(IThrowableProxy throwable, int maxFrames) {
        StringBuilder sb = new StringBuilder(1024);
        append(sb, throwable, "", maxFrames, Collections.newSetFromMap(new IdentityHashMap<>()));
        return sb.toString();
    }

    private static void append(StringBuilder sb, IThrowableProxy t, String prefix, int maxFrames,
                               Set<IThrowableProxy> seen) {
        if (!seen.add(t)) {
            sb.append(prefix).append("[circular reference] ").append(t.getClassName()).append('\n');
            return;
        }
        sb.append(prefix).append(t.getClassName());
        if (t.getMessage() != null) {
            // Continuation lines are indented like stack frames, so message text never starts a line.
            sb.append(": ").append(t.getMessage().replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\n\t"));
        }
        sb.append('\n');

        StackTraceElementProxy[] frames = t.getStackTraceElementProxyArray();
        int unique = Math.max(0, frames.length - t.getCommonFrames());
        int shown = maxFrames > 0 ? Math.min(maxFrames, unique) : unique;
        for (int i = 0; i < shown; i++) {
            sb.append("\tat ").append(frames[i].getStackTraceElement()).append('\n');
        }
        if (frames.length > shown) {
            sb.append("\t... ").append(frames.length - shown).append(" more\n");
        }

        IThrowableProxy[] suppressed = t.getSuppressed();
        if (suppressed != null) {
            for (IThrowableProxy s : suppressed) {
                append(sb, s, "Suppressed: ", maxFrames, seen);
            }
        }
        if (t.getCause() != null) {
            append(sb, t.getCause(), "Caused by: ", maxFrames, seen);
        }
    }
}
