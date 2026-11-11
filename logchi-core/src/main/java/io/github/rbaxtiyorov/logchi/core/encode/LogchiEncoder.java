package io.github.rbaxtiyorov.logchi.core.encode;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import io.github.rbaxtiyorov.logchi.core.mask.Masker;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Shared masking and byte conversion for the text and JSON encoders. */
abstract class LogchiEncoder extends EncoderBase<ILoggingEvent> {

    private static final byte[] EMPTY = new byte[0];

    protected final Masker masker;
    protected final int stackTraceLines;

    LogchiEncoder(Masker masker, int stackTraceLines) {
        this.masker = masker;
        this.stackTraceLines = stackTraceLines;
    }

    @Override
    public final byte[] encode(ILoggingEvent event) {
        String line;
        try {
            line = render(event);
        } catch (Throwable failure) {
            // Whatever went wrong, the caller's log statement must not fail and the writer thread must survive.
            // The message itself is left out: it could not be masked.
            line = fallback(event, failure);
        }
        return line.getBytes(StandardCharsets.UTF_8);
    }

    /** One safe line or object saying that an event could not be rendered. */
    abstract String fallback(ILoggingEvent event, Throwable failure);

    @Override
    public final byte[] headerBytes() {
        return EMPTY;
    }

    @Override
    public final byte[] footerBytes() {
        return EMPTY;
    }

    abstract String render(ILoggingEvent event);

    static final int ONE_LINE = 0;
    static final int INDENTED = 1;
    static final int AS_IS = 2;

    private record Masked(ILoggingEvent event, int shape, Masker masker, String text) {
    }

    /**
     * An event is rendered once per sink: console, main file, level file. Masking is the expensive part,
     * so the result is shared. A fixed number of slots, overwritten on collision, never grows.
     */
    private static final AtomicReferenceArray<Masked> RECENT = new AtomicReferenceArray<>(512);

    /** The event's message in the given shape, masked. */
    final String maskedMessage(ILoggingEvent event, int shape) {
        int slot = (System.identityHashCode(event) * 31 + shape) & (RECENT.length() - 1);
        Masked known = RECENT.get(slot);
        if (known != null && known.event() == event && known.shape() == shape && known.masker() == masker) {
            return known.text();
        }
        String message = event.getFormattedMessage();
        String shaped = shape == ONE_LINE ? oneLine(message) : shape == INDENTED ? indented(message) : message;
        String text = masker.mask(shaped);
        RECENT.set(slot, new Masked(event, shape, masker, text));
        return text;
    }

    /** Escapes line breaks so one event stays one line and user input cannot forge log entries. */
    static String oneLine(String value) {
        if (value == null) {
            return "null";
        }
        if (value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }

    /** Continuation lines start with a tab, so text inside a message can never pass for a new event. */
    static String indented(String value) {
        if (value == null) {
            return "null";
        }
        if (value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return value.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\n\t");
    }

    /** Masks a structured field by key, falling back to scanning its value. */
    final String maskField(String key, String value) {
        return masker.isSensitiveKey(key) ? masker.replacement() : masker.mask(value);
    }
}
