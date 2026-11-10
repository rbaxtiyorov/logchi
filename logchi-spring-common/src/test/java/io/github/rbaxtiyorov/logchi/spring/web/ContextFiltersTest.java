package io.github.rbaxtiyorov.logchi.spring.web;

import io.github.rbaxtiyorov.logchi.core.LogchiSettings;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextFiltersTest {

    private final LogchiSettings.Context options = new LogchiSettings.Context();
    private final Map<String, String> seen = new HashMap<>();
    private final FilterChain capture = (req, res) -> {
        Map<String, String> copy = MDC.getCopyOfContextMap();
        if (copy != null) {
            seen.putAll(copy);
        }
    };

    @AfterEach
    void clear() {
        MDC.clear();
    }

    private MockHttpServletResponse runContext(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ServletContextFilter(options).doFilter(request, response, capture);
        return response;
    }

    @Test
    void generatesTraceIdAndEchoesIt() throws Exception {
        MockHttpServletResponse response = runContext(new MockHttpServletRequest());
        assertTrue(seen.get("traceId").matches("[0-9a-f]{16}"), seen.toString());
        assertEquals(seen.get("traceId"), response.getHeader("X-Request-Id"));
    }

    @Test
    void reusesWellFormedIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "abc-123");
        runContext(request);
        assertEquals("abc-123", seen.get("traceId"));
    }

    @Test
    void rejectsMaliciousIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "abc\r\nSet-Cookie: x=1");
        MockHttpServletResponse response = runContext(request);
        assertNotEquals("abc\r\nSet-Cookie: x=1", seen.get("traceId"));
        assertFalse(response.getHeader("X-Request-Id").contains("\n"));
    }

    @Test
    void clearsMdcAfterwardsEvenOnFailure() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("x");
        };
        try {
            new ServletContextFilter(options).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing);
        } catch (Exception expected) {
            assertNull(MDC.get("traceId"));
            return;
        }
        throw new AssertionError("should have thrown");
    }

    @Test
    void keepsTraceIdAlreadySetByTracing() throws Exception {
        MDC.put("traceId", "from-tracing");
        MockHttpServletResponse response = runContext(new MockHttpServletRequest());
        assertEquals("from-tracing", seen.get("traceId"));
        assertEquals("from-tracing", response.getHeader("X-Request-Id"));
        assertEquals("from-tracing", MDC.get("traceId"), "whoever set it removes it");
    }

    @Test
    void extraHeadersBecomeCleanMdcKeys() throws Exception {
        options.setExtraHeaders(List.of("X-Tenant-Id"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "acme\nfake");
        runContext(request);
        assertEquals("acmefake", seen.get("tenantId"));
    }

    @Test
    void userComesFromPrincipal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "alice");
        new ServletUserFilter(options).doFilter(request, new MockHttpServletResponse(), capture);
        assertEquals("alice", seen.get("userId"));
        assertNull(MDC.get("userId"));
    }

    @Test
    void userCanComeFromHeader() throws Exception {
        options.setUserId("header:X-User");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User", "bob");
        new ServletUserFilter(options).doFilter(request, new MockHttpServletResponse(), capture);
        assertEquals("bob", seen.get("userId"));
    }

    @Test
    void userCanBeTurnedOff() throws Exception {
        options.setUserId("none");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "alice");
        new ServletUserFilter(options).doFilter(request, new MockHttpServletResponse(), capture);
        assertNull(seen.get("userId"));
    }

    @Test
    void reactiveFilterSetsHeaderAndReactorContext() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/").header("X-Request-Id", "abc-123"));
        AtomicReference<String> inContext = new AtomicReference<>();
        new ReactiveContextFilter(options).filter(exchange, ex -> Mono.deferContextual(c -> {
            inContext.set(c.get(ReactiveContextFilter.TRACE_ID));
            return Mono.<Void>empty();
        })).block();
        assertEquals("abc-123", inContext.get());
        assertEquals("abc-123", exchange.getResponse().getHeaders().getFirst("X-Request-Id"));
    }

    @Test
    void mdcTravelsToWorkerAndIsRestored() {
        MDC.put("traceId", "t1");
        Runnable decorated = new MdcTaskDecorator().decorate(() -> seen.put("worker", MDC.get("traceId")));
        MDC.put("traceId", "worker-own");
        decorated.run();
        assertEquals("t1", seen.get("worker"));
        assertEquals("worker-own", MDC.get("traceId"));
    }

    @Test
    void headerNamesMapToMdcKeys() {
        assertEquals("tenantId", ContextValues.mdcKey("X-Tenant-Id"));
        assertEquals("region", ContextValues.mdcKey("Region"));
    }
}
