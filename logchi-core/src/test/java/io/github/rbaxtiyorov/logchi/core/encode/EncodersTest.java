package io.github.rbaxtiyorov.logchi.core.encode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rbaxtiyorov.logchi.core.LogchiSettings;
import io.github.rbaxtiyorov.logchi.core.LogchiSettings.JsonFlavor;
import io.github.rbaxtiyorov.logchi.core.mask.Masker;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncodersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LoggerContext context = new LoggerContext();
    private final Masker masker = Masker.from(new LogchiSettings.Mask());

    {
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
    }

    private LoggingEvent event(Level level, String message, Throwable t) {
        LoggingEvent e = new LoggingEvent("fqcn", context.getLogger("com.example.OrderService"), level, message, t, null);
        e.setThreadName("worker-1");
        return e;
    }

    private String text(LoggingEvent e) {
        return new String(new TextEncoder(masker, 30, false).encode(e), StandardCharsets.UTF_8);
    }

    private JsonNode json(JsonFlavor flavor, LoggingEvent e) throws Exception {
        String line = new String(new JsonEncoder(flavor, masker, 30, "orders", "pod-1").encode(e), StandardCharsets.UTF_8);
        assertTrue(line.endsWith("}\n"));
        assertEquals(1, line.split("\n", -1).length - 1, "exactly one line");
        return JSON.readTree(line);
    }

    @Test
    void textRendersContextAndKeyValues() {
        LoggingEvent e = event(Level.INFO, "order created", null);
        e.setMDCPropertyMap(Map.of("traceId", "7f3a", "userId", "42"));
        e.addKeyValuePair(new KeyValuePair("orderId", 42));
        e.addKeyValuePair(new KeyValuePair("note", "two words"));
        String line = text(e);
        assertTrue(line.contains("INFO  [worker-1] [tid=7f3a uid=42] "), line);
        assertTrue(line.endsWith(" : order created orderId=42 note=\"two words\"\n"), line);
    }

    @Test
    void textKeepsAnEventOnOneLineSoInputCannotForgeEntries() {
        String line = text(event(Level.INFO, "name=bob\n2026-01-01 ERROR fake entry", null));
        assertEquals(1, line.split("\n", -1).length - 1);
        assertTrue(line.contains("bob\\n2026-01-01"), line);
    }

    @Test
    void textMasksMessagesFieldsAndStackTraces() {
        LoggingEvent e = event(Level.ERROR, "login password=abc", new IllegalStateException("bad token=xyz"));
        e.addKeyValuePair(new KeyValuePair("cardNumber", "4111"));
        String out = text(e);
        assertFalse(out.contains("abc"), out);
        assertFalse(out.contains("xyz"), out);
        assertTrue(out.contains("cardNumber=***"), out);
    }

    @Test
    void stackTraceLimitNeverLosesTheCause() {
        Throwable root = new IllegalArgumentException("root problem");
        String out = new String(new TextEncoder(masker, 2, false).encode(
                event(Level.ERROR, "failed", new RuntimeException("wrapper", root))), StandardCharsets.UTF_8);
        assertTrue(out.contains("Caused by: java.lang.IllegalArgumentException: root problem"), out);
        assertTrue(out.contains("more"), out);
    }

    @Test
    void ecsIsValidJsonWithTypedFields() throws Exception {
        LoggingEvent e = event(Level.WARN, "quote \" slash \\ newline \n tab \t ctl \u0001 uni é", null);
        e.setMDCPropertyMap(Map.of("traceId", "7f3a", "tenantId", "acme"));
        e.addKeyValuePair(new KeyValuePair("orderId", 42));
        e.addKeyValuePair(new KeyValuePair("paid", true));
        e.addKeyValuePair(new KeyValuePair("ratio", Double.NaN));
        JsonNode n = json(JsonFlavor.ECS, e);
        assertEquals("quote \" slash \\ newline \n tab \t ctl \u0001 uni é", n.get("message").asText());
        assertEquals("WARN", n.get("log.level").asText());
        assertEquals("7f3a", n.get("trace.id").asText());
        assertEquals("acme", n.get("tenantId").asText());
        assertTrue(n.get("orderId").isNumber());
        assertTrue(n.get("paid").isBoolean());
        assertEquals("NaN", n.get("ratio").asText());
        assertEquals("orders", n.get("service.name").asText());
    }

    @Test
    void jsonCarriesErrorsAndMasks() throws Exception {
        LoggingEvent e = event(Level.ERROR, "password=abc", new IllegalStateException("boom"));
        e.addKeyValuePair(new KeyValuePair("apiKey", "s3cret"));
        JsonNode n = json(JsonFlavor.ECS, e);
        assertEquals("password=***", n.get("message").asText());
        assertEquals("***", n.get("apiKey").asText());
        assertEquals("java.lang.IllegalStateException", n.get("error.type").asText());
        assertTrue(n.get("error.stack_trace").asText().contains("boom"));
    }

    @Test
    void userKeysCannotCollideWithReservedFields() throws Exception {
        LoggingEvent e = event(Level.INFO, "real", null);
        e.addKeyValuePair(new KeyValuePair("message", "fake"));
        JsonNode n = json(JsonFlavor.ECS, e);
        assertEquals("real", n.get("message").asText());
        assertEquals("fake", n.get("kv_message").asText());
    }

    @Test
    void otherFlavorsUseTheirOwnFieldNames() throws Exception {
        JsonNode logstash = json(JsonFlavor.LOGSTASH, event(Level.INFO, "hi", null));
        assertEquals("1", logstash.get("@version").asText());
        assertEquals("com.example.OrderService", logstash.get("logger_name").asText());
        assertEquals(20000, logstash.get("level_value").asInt());

        JsonNode compact = json(JsonFlavor.COMPACT, event(Level.INFO, "hi", null));
        assertEquals("hi", compact.get("msg").asText());
        assertTrue(compact.get("ts").isNumber());
    }
}
