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
    void clearsMdcAfterASuccessfulRequest() throws Exception {
        runContext(new MockHttpServletRequest());
        assertNull(MDC.get("traceId"));
    }

    /** The servlet container logs a failed request after every filter has returned. */
    @Test
    void keepsContextForTheContainerAfterAFailureThenCleansUp() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("x");
        };
        try {
            new ServletContextFilter(options).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing);
            throw new AssertionError("should have thrown");
        } catch (Exception expected) {
            assertTrue(MDC.get("traceId").matches("[0-9a-f]{16}"), "still there for the container's error line");
        }
        new ServletContextFilter.Cleanup().requestDestroyed(null);
        assertNull(MDC.get("traceId"));
    }

    @Test
    void aFailedRequestNeverLeaksIntoTheNextOne() throws Exception {
        FilterChain failing = (req, res) -> {
            MDC.put("userId", "alice");
            req.setAttribute(ServletContextFilter.USER_SET, Boolean.TRUE);
            throw new IllegalStateException("x");
        };
        try {
            new ServletContextFilter(options).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing);
        } catch (Exception expected) {
            // The cleanup listener did not run, as after an asynchronous request.
        }
        String first = MDC.get("traceId");
        runContext(new MockHttpServletRequest());
        assertNotEquals(first, seen.get("traceId"));
        assertNull(seen.get("userId"));
    }

    @Test
    void filterOrderIsConfigurable() {
        assertEquals(Integer.MIN_VALUE + 10, new ServletContextFilter(options).getOrder());
        options.setOrder(-500);
        assertEquals(-500, new ServletContextFilter(options).getOrder());
        assertEquals(-500, new ReactiveContextFilter(options).getOrder());
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
    }

    /** A filter between the two, such as a request logger, must still know the user when it writes its line. */
    @Test
    void userStaysVisibleOnTheWayOutAndIsRemovedAtTheEnd() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "alice");
        AtomicReference<String> onTheWayOut = new AtomicReference<>();
        FilterChain between = (req, res) -> {
            new ServletUserFilter(options).doFilter(req, res, capture);
            onTheWayOut.set(MDC.get("userId"));
        };
        new ServletContextFilter(options).doFilter(request, new MockHttpServletResponse(), between);
        assertEquals("alice", onTheWayOut.get());
        assertNull(MDC.get("userId"));
        assertNull(MDC.get("traceId"));
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

    @Test
    void aUserIdTheApplicationSetItselfIsLeftAlone() throws Exception {
        MDC.put("userId", "set-by-the-app");
        runContext(new MockHttpServletRequest());
        assertEquals("set-by-the-app", MDC.get("userId"));
    }

    /** An application with its own request id filter ahead of this one must end up with a single id. */
    @Test
    void adoptsAnIdAnOuterFilterAlreadyIssued() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "from-the-client");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-Request-Id", "df96e144");
        new ServletContextFilter(options).doFilter(request, response, capture);
        assertEquals("df96e144", seen.get("traceId"));
        assertEquals("df96e144", response.getHeader("X-Request-Id"));
    }

    /** The second half of an asynchronous request runs on another thread and needs the same context. */
    @Test
    void anAsyncDispatchGetsTheSameRequestContext() throws Exception {
        options.setExtraHeaders(List.of("X-Tenant-Id"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "acme");
        request.setUserPrincipal(() -> "alice");
        ServletContextFilter filter = new ServletContextFilter(options);
        FilterChain withUser = (req, res) -> new ServletUserFilter(options).doFilter(req, res, capture);
        filter.doFilter(request, new MockHttpServletResponse(), withUser);
        String traceId = seen.get("traceId");
        assertNull(MDC.get("traceId"));

        seen.clear();
        request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
        filter.doFilter(request, new MockHttpServletResponse(), capture);
        assertEquals(traceId, seen.get("traceId"));
        assertEquals("alice", seen.get("userId"));
        assertEquals("acme", seen.get("tenantId"));
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("userId"));
    }
}
