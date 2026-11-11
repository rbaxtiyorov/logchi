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

    @Test
    void chosenLoggersKeepLineBreaksAndIndentThem() {
        LoggingEvent e = event(Level.INFO, "REQ\nbody line\r\n2026-11-10 ERROR forged", null);
        String out = new String(new TextEncoder(masker, 30, false, java.util.List.of("com.example")).encode(e),
                StandardCharsets.UTF_8);
        assertTrue(out.endsWith(" : REQ\n\tbody line\n\t2026-11-10 ERROR forged\n"), out);
        for (String line : out.split("\n")) {
            assertTrue(line.startsWith("20") && line.contains(" INFO ") || line.startsWith("\t"),
                    "every continuation line is indented: " + line);
        }
    }

    @Test
    void otherLoggersStillGetOneLine() {
        LoggingEvent e = event(Level.INFO, "a\nb", null);
        String out = new String(new TextEncoder(masker, 30, false, java.util.List.of("com.other", "com.exam")).encode(e),
                StandardCharsets.UTF_8);
        assertTrue(out.endsWith(" : a\\nb\n"), out);
    }

    @Test
    void masksInsideMultiLineMessages() {
        LoggingEvent e = event(Level.INFO, "RES\n{\"refreshToken\":\"abc\"}", null);
        String out = new String(new TextEncoder(masker, 30, false, java.util.List.of("com.example.OrderService"))
                .encode(e), StandardCharsets.UTF_8);
        assertTrue(out.contains("\n\t{\"refreshToken\":\"***\"}"), out);
    }

    /** An exception message is as untrusted as a log message: a database error can quote user input. */
    @Test
    void exceptionMessagesCannotForgeLines() {
        Throwable t = new IllegalStateException("bad input\n2026-11-11 10:00:00.000 INFO  [main] forged\r\nmore");
        String out = text(event(Level.ERROR, "failed", t));
        String[] lines = out.split("\n");
        assertTrue(lines[1].startsWith("java.lang.IllegalStateException: bad input"), out);
        assertEquals("\t2026-11-11 10:00:00.000 INFO  [main] forged", lines[2]);
        assertEquals("\tmore", lines[3]);
        for (int i = 2; i < lines.length; i++) {
            assertTrue(lines[i].startsWith("\t") || lines[i].startsWith("Caused by: "), lines[i]);
        }
    }

    @Test
    void timestampsCarryTheirOffset() {
        String line = text(event(Level.INFO, "x", null));
        assertTrue(line.matches("(?s)\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}(Z|[+-]\\d{2}:\\d{2}) INFO .*"), line);
    }

    /** Whatever breaks while rendering, the log call returns and the writer thread lives. */
    @Test
    void aFailureWhileRenderingBecomesAMarkerLine() {
        LoggingEvent e = event(Level.WARN, "secret stuff", null);
        TextEncoder real = new TextEncoder(masker, 30, false);
        LogchiEncoder broken = new LogchiEncoder(masker, 30) {
            @Override
            String render(ch.qos.logback.classic.spi.ILoggingEvent event) {
                throw new StackOverflowError();
            }

            @Override
            String fallback(ch.qos.logback.classic.spi.ILoggingEvent event, Throwable failure) {
                return real.fallback(event, failure);
            }
        };
        String out = new String(broken.encode(e), StandardCharsets.UTF_8);
        assertTrue(out.endsWith("com.example.OrderService : [logchi could not render this event: StackOverflowError]\n"), out);
        assertFalse(out.contains("secret stuff"));
    }

    @Test
    void threadNamesCannotForgeLines() {
        LoggingEvent e = new LoggingEvent("fqcn", context.getLogger("com.example.OrderService"), Level.INFO, "x",
                null, null);
        e.setThreadName("job\n2026-11-11 10:00:00.000 INFO  [main] forged");
        String out = text(e);
        assertEquals(1, out.split("\n").length, out);
        assertTrue(out.contains("[job\\n2026-11-11 10:00:00.000 INFO  [main] forged]"), out);
    }

    /** The message is masked once however many sinks render the event, and never with another masker's rules. */
    @Test
    void sinksShareOneMaskedMessageButNotAcrossMaskers() {
        LoggingEvent e = event(Level.INFO, "login password=hunter2 ok\nnext", null);
        TextEncoder main = new TextEncoder(masker, 30, false);
        TextEncoder error = new TextEncoder(masker, 30, false);
        TextEncoder multi = new TextEncoder(masker, 30, false, java.util.List.of("com.example"));
        TextEncoder unmasked = new TextEncoder(Masker.NONE, 30, false);
        String first = new String(main.encode(e), StandardCharsets.UTF_8);
        assertEquals(first, new String(error.encode(e), StandardCharsets.UTF_8));
        assertTrue(first.endsWith(" : login password=*** ok\\nnext\n"), first);
        assertTrue(new String(multi.encode(e), StandardCharsets.UTF_8).endsWith(" : login password=*** ok\n\tnext\n"));
        assertTrue(new String(unmasked.encode(e), StandardCharsets.UTF_8).contains("hunter2"));
        assertFalse(new String(main.encode(e), StandardCharsets.UTF_8).contains("hunter2"));
    }

    @Test
    void jsonStaysValidUnderConcurrentUse() throws Exception {
        JsonEncoder encoder = new JsonEncoder(JsonFlavor.ECS, masker, 30, "orders", "pod-1");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int thread = t;
            results.add(pool.submit(() -> {
                int bad = 0;
                for (int i = 0; i < 2_000; i++) {
                    LoggingEvent e = event(Level.INFO, "order " + thread + "-" + i + " \"quoted\"\npassword=p" + i, null);
                    e.addKeyValuePair(new KeyValuePair("n", i));
                    JsonNode node = JSON.readTree(new String(encoder.encode(e), StandardCharsets.UTF_8));
                    if (!node.get("message").asText().equals("order " + thread + "-" + i + " \"quoted\"\npassword=***")) {
                        bad++;
                    }
                }
                return bad;
            }));
        }
        int bad = 0;
        for (java.util.concurrent.Future<Integer> result : results) {
            bad += result.get();
        }
        pool.shutdown();
        assertEquals(0, bad);
    }
}
