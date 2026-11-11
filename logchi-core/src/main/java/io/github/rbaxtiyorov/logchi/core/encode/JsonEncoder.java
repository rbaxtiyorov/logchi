package io.github.rbaxtiyorov.logchi.core.encode;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.rbaxtiyorov.logchi.core.LogchiSettings.JsonFlavor;
import io.github.rbaxtiyorov.logchi.core.mask.Masker;
import org.slf4j.event.KeyValuePair;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One JSON object per line. MDC and SLF4J key-values are flattened into top-level fields. */
public final class JsonEncoder extends LogchiEncoder {

    private static final Set<String> ECS_RESERVED = Set.of(
            "@timestamp", "ecs.version", "log.level", "log.logger", "message", "process.thread.name",
            "service.name", "service.instance.id", "trace.id", "user.id", "error.type", "error.message",
            "error.stack_trace");
    private static final Set<String> LOGSTASH_RESERVED = Set.of(
            "@timestamp", "@version", "message", "logger_name", "thread_name", "level", "level_value",
            "stack_trace", "service", "instance");
    private static final Set<String> COMPACT_RESERVED = Set.of(
            "ts", "lvl", "log", "msg", "th", "err", "svc", "inst");

    private final JsonFlavor flavor;
    private final String service;
    private final String instance;

    public JsonEncoder(JsonFlavor flavor, Masker masker, int stackTraceLines, String service, String instance) {
        super(masker, stackTraceLines);
        this.flavor = flavor;
        this.service = service;
        this.instance = instance;
    }

    @Override
    String fallback(ILoggingEvent e, Throwable failure) {
        return new JsonWriter().field("@timestamp", Instant.ofEpochMilli(e.getTimeStamp()).toString())
                .field("level", e.getLevel().toString())
                .field("logger", e.getLoggerName())
                .field("message", "[logchi could not render this event: " + failure.getClass().getSimpleName() + "]")
                .finish();
    }

    @Override
    String render(ILoggingEvent e) {
        JsonWriter w = new JsonWriter();
        Map<String, Object> custom = customFields(e);
        switch (flavor) {
            case ECS -> writeEcs(w, e, custom);
            case LOGSTASH -> writeLogstash(w, e, custom);
            case COMPACT -> writeCompact(w, e, custom);
        }
        return w.finish();
    }

    private void writeEcs(JsonWriter w, ILoggingEvent e, Map<String, Object> custom) {
        w.field("@timestamp", Instant.ofEpochMilli(e.getTimeStamp()).toString())
                .field("ecs.version", "8.11")
                .field("log.level", e.getLevel().toString())
                .field("log.logger", e.getLoggerName())
                .field("message", maskedMessage(e, AS_IS))
                .field("process.thread.name", e.getThreadName())
                .field("service.name", service)
                .field("service.instance.id", instance);
        Object traceId = custom.remove("traceId");
        if (traceId != null) {
            w.field("trace.id", traceId.toString());
        }
        Object userId = custom.remove("userId");
        if (userId != null) {
            w.field("user.id", userId.toString());
        }
        IThrowableProxy t = e.getThrowableProxy();
        if (t != null) {
            w.field("error.type", t.getClassName())
                    .field("error.message", masker.mask(t.getMessage()))
                    .field("error.stack_trace", stack(t));
        }
        writeCustom(w, custom, ECS_RESERVED);
    }

    private void writeLogstash(JsonWriter w, ILoggingEvent e, Map<String, Object> custom) {
        w.field("@timestamp", Instant.ofEpochMilli(e.getTimeStamp()).toString())
                .field("@version", "1")
                .field("message", maskedMessage(e, AS_IS))
                .field("logger_name", e.getLoggerName())
                .field("thread_name", e.getThreadName())
                .field("level", e.getLevel().toString())
                .field("level_value", (long) e.getLevel().toInt())
                .field("service", service)
                .field("instance", instance);
        if (e.getThrowableProxy() != null) {
            w.field("stack_trace", stack(e.getThrowableProxy()));
        }
        writeCustom(w, custom, LOGSTASH_RESERVED);
    }

    private void writeCompact(JsonWriter w, ILoggingEvent e, Map<String, Object> custom) {
        w.field("ts", e.getTimeStamp())
                .field("lvl", e.getLevel().toString())
                .field("log", e.getLoggerName())
                .field("msg", maskedMessage(e, AS_IS))
                .field("th", e.getThreadName())
                .field("svc", service)
                .field("inst", instance);
        if (e.getThrowableProxy() != null) {
            w.field("err", stack(e.getThrowableProxy()));
        }
        writeCustom(w, custom, COMPACT_RESERVED);
    }

    private String stack(IThrowableProxy t) {
        return masker.mask(ThrowableRenderer.render(t, stackTraceLines));
    }

    private static Map<String, Object> customFields(ILoggingEvent e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, String> mdc = e.getMDCPropertyMap();
        if (mdc != null) {
            fields.putAll(mdc);
        }
        List<KeyValuePair> pairs = e.getKeyValuePairs();
        if (pairs != null) {
            pairs.forEach(p -> fields.put(p.key, p.value));
        }
        return fields;
    }

    private void writeCustom(JsonWriter w, Map<String, Object> custom, Set<String> reserved) {
        custom.forEach((key, value) -> {
            String name = reserved.contains(key) ? "kv_" + key : key;
            if (masker.isSensitiveKey(key)) {
                w.field(name, masker.replacement());
            } else if (value instanceof Number number) {
                w.field(name, number);
            } else if (value instanceof Boolean bool) {
                w.field(name, bool.booleanValue());
            } else {
                w.field(name, masker.mask(String.valueOf(value)));
            }
        });
    }
}
