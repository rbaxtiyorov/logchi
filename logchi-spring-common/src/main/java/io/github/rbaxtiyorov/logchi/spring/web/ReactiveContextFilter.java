package io.github.rbaxtiyorov.logchi.spring.web;

import io.github.rbaxtiyorov.logchi.core.LogchiSettings;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Adds the trace id to the Reactor context and the response. Reaching the MDC additionally requires
 * {@code spring.reactor.context-propagation=auto}, because a reactive request hops between threads.
 */
public class ReactiveContextFilter implements WebFilter, Ordered {

    public static final String TRACE_ID = "traceId";

    private final LogchiSettings.Context options;

    public ReactiveContextFilter(LogchiSettings.Context options) {
        this.options = options;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!options.isTraceId()) {
            return chain.filter(exchange);
        }
        String traceId = ContextValues.traceId(exchange.getRequest().getHeaders().getFirst(options.getHeader()));
        exchange.getResponse().getHeaders().set(options.getHeader(), traceId);
        return chain.filter(exchange).contextWrite(context -> context.put(TRACE_ID, traceId));
    }
}
