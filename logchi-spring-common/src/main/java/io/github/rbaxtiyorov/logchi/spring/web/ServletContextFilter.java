package io.github.rbaxtiyorov.logchi.spring.web;

import io.github.rbaxtiyorov.logchi.core.LogchiSettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts the trace id and configured headers in the MDC, early enough to cover every later filter, and
 * removes the whole request context, the user included, when the request leaves.
 *
 * <p>When the request fails with an exception the context is left in place a little longer, because the
 * servlet container logs that failure after every filter has returned. {@link Cleanup} removes it when
 * the container is done with the request.
 */
public class ServletContextFilter extends OncePerRequestFilter implements Ordered {

    static final String USER_ID = "userId";
    static final String USER_SET = ServletContextFilter.class.getName() + ".userSet";
    private static final String CONTEXT = ServletContextFilter.class.getName() + ".context";

    /** Keys left in the MDC by a failed request on this thread. */
    private static final ThreadLocal<List<String>> LEFTOVER = new ThreadLocal<>();

    private final LogchiSettings.Context options;

    public ServletContextFilter(LogchiSettings.Context options) {
        this.options = options;
    }

    @Override
    public int getOrder() {
        return options.getOrder();
    }

    /** A request handled asynchronously comes back on another thread, which needs the context again. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        clearLeftover();
        Map<String, String> context = (Map<String, String>) request.getAttribute(CONTEXT);
        List<String> added = new ArrayList<>();
        if (context != null) {
            // The asynchronous part of a request already seen: same id, same user.
            context.forEach((key, value) -> {
                if (MDC.get(key) == null) {
                    MDC.put(key, value);
                    added.add(key);
                }
            });
        } else {
            context = new LinkedHashMap<>();
            request.setAttribute(CONTEXT, context);
            if (options.isTraceId()) {
                String traceId = MDC.get("traceId");
                // Keep an id already set by Micrometer Tracing, and leave its removal to it.
                if (traceId == null) {
                    // An application filter ahead of this one may have issued its own id already.
                    String issued = response.getHeader(options.getHeader());
                    traceId = ContextValues.traceId(issued != null ? issued : request.getHeader(options.getHeader()));
                    MDC.put("traceId", traceId);
                    added.add("traceId");
                    context.put("traceId", traceId);
                }
                response.setHeader(options.getHeader(), traceId);
            }
            for (String header : options.getExtraHeaders()) {
                String value = request.getHeader(header);
                if (value != null) {
                    String key = ContextValues.mdcKey(header);
                    MDC.put(key, ContextValues.clean(value));
                    added.add(key);
                    context.put(key, MDC.get(key));
                }
            }
        }
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            // The user is added further in, by ServletUserFilter, and stays visible to filters on the way out.
            if (request.getAttribute(USER_SET) != null && !added.contains(USER_ID)) {
                added.add(USER_ID);
                String user = MDC.get(USER_ID);
                if (user != null) {
                    context.put(USER_ID, user);
                }
            }
            if (failed) {
                LEFTOVER.set(added);
            } else {
                added.forEach(MDC::remove);
            }
        }
    }

    static void clearLeftover() {
        List<String> keys = LEFTOVER.get();
        if (keys != null) {
            keys.forEach(MDC::remove);
            LEFTOVER.remove();
        }
    }

    /** Removes what a failed request left behind, once the container has logged the failure. */
    public static class Cleanup implements ServletRequestListener {

        @Override
        public void requestDestroyed(ServletRequestEvent event) {
            clearLeftover();
        }
    }
}
