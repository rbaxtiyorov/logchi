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
import java.security.Principal;

/** Puts the user id in the MDC. Runs just after Spring Security's chain, which sets the principal. */
public class ServletUserFilter extends OncePerRequestFilter implements Ordered {

    private static final int AFTER_SECURITY = -99;
    private static final String HEADER_PREFIX = "header:";

    private final LogchiSettings.Context options;

    public ServletUserFilter(LogchiSettings.Context options) {
        this.options = options;
    }

    @Override
    public int getOrder() {
        return AFTER_SECURITY;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String user = resolve(request);
        if (user == null) {
            chain.doFilter(request, response);
            return;
        }
        MDC.put("userId", ContextValues.clean(user));
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("userId");
        }
    }

    private String resolve(HttpServletRequest request) {
        String mode = options.getUserId();
        if ("principal".equals(mode)) {
            Principal principal = request.getUserPrincipal();
            return principal != null ? principal.getName() : null;
        }
        if (mode != null && mode.startsWith(HEADER_PREFIX)) {
            return request.getHeader(mode.substring(HEADER_PREFIX.length()));
        }
        return null;
    }
}
