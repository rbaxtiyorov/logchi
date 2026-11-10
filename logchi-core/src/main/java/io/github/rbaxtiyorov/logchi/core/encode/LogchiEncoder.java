package io.github.rbaxtiyorov.logchi.core.encode;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import io.github.rbaxtiyorov.logchi.core.mask.Masker;

import java.nio.charset.StandardCharsets;

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
        return render(event).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public final byte[] headerBytes() {
        return EMPTY;
    }

    @Override
    public final byte[] footerBytes() {
        return EMPTY;
    }

    abstract String render(ILoggingEvent event);

    /** Masks a structured field by key, falling back to scanning its value. */
    final String maskField(String key, String value) {
        return masker.isSensitiveKey(key) ? masker.replacement() : masker.mask(value);
    }
}
