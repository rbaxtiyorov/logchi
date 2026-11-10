package io.github.rbaxtiyorov.logchi.spring.web;

import io.github.rbaxtiyorov.logchi.core.LogchiSettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Puts the trace id and configured headers in the MDC, early enough to cover every later filter. */
public class ServletContextFilter extends OncePerRequestFilter implements Ordered {

    private final LogchiSettings.Context options;

    public ServletContextFilter(LogchiSettings.Context options) {
        this.options = options;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> added = new ArrayList<>();
        if (options.isTraceId()) {
            String traceId = MDC.get("traceId");
            // Keep an id already set by Micrometer Tracing, and leave its removal to it.
            if (traceId == null) {
                traceId = ContextValues.traceId(request.getHeader(options.getHeader()));
                MDC.put("traceId", traceId);
                added.add("traceId");
            }
            response.setHeader(options.getHeader(), traceId);
        }
        for (String header : options.getExtraHeaders()) {
            String value = request.getHeader(header);
            if (value != null) {
                String key = ContextValues.mdcKey(header);
                MDC.put(key, ContextValues.clean(value));
                added.add(key);
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            added.forEach(MDC::remove);
        }
    }
}
