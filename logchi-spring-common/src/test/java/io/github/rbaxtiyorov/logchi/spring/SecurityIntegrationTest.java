package io.github.rbaxtiyorov.logchi.spring;

import io.github.rbaxtiyorov.logchi.core.LogchiInstaller;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a real server with Spring Security as an OAuth2 resource server, the setup a JWT-protected API has.
 * The request filter here stands in for an application's own request logger: registered ahead of the
 * security chain, writing its line after the chain returns.
 */
class SecurityIntegrationTest {

    @TempDir
    static Path dir;

    private static ConfigurableApplicationContext context;
    private static String base;

    /** What the application's own filter saw after the chain returned, by request path. */
    private static final Map<String, Map<String, String>> ON_THE_WAY_OUT = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> IN_CONTROLLER = new ConcurrentHashMap<>();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class App {

        @Bean
        SecurityFilterChain api(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()));
            return http.build();
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                if (!"good".equals(token)) {
                    throw new BadJwtException("unknown token");
                }
                return Jwt.withTokenValue(token).header("alg", "none").subject("user-42").build();
            };
        }

        @Bean
        RequestLogger requestLogger() {
            return new RequestLogger();
        }

        @Bean
        Api apiController() {
            return new Api();
        }
    }

    static class RequestLogger implements Filter, Ordered {

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 20;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            try {
                chain.doFilter(request, response);
            } finally {
                Map<String, String> mdc = MDC.getCopyOfContextMap();
                ON_THE_WAY_OUT.put(((HttpServletRequest) request).getRequestURI(), mdc == null ? Map.of() : mdc);
            }
        }
    }

    @RestController
    static class Api {

        private static final Logger log = LoggerFactory.getLogger(Api.class);

        @GetMapping("/me")
        String me() {
            IN_CONTROLLER.put("/me", MDC.getCopyOfContextMap());
            log.info("handling me");
            return "ok";
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("kaboom");
        }
    }

    @BeforeAll
    static void start() {
        SpringApplication app = new SpringApplication(App.class);
        context = app.run("--server.port=0", "--logchi.dir=" + dir, "--logchi.instance-id=t1",
                "--spring.application.name=api", "--logchi.file.retention.min-free-disk=0",
                "--logchi.file.async.enabled=false");
        base = "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        LogchiInstaller.uninstall();
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String log() throws IOException {
        return Files.readString(dir.resolve("api.t1.txt"));
    }

    @Test
    void theJwtSubjectIsTheUserAndStaysVisibleToOuterFilters() throws Exception {
        HttpResponse<String> response = get("/me", "good");
        assertEquals(200, response.statusCode());
        String traceId = response.headers().firstValue("X-Request-Id").orElseThrow();

        assertEquals("user-42", IN_CONTROLLER.get("/me").get("userId"));
        assertEquals(traceId, IN_CONTROLLER.get("/me").get("traceId"));
        assertEquals("user-42", ON_THE_WAY_OUT.get("/me").get("userId"), "a request logger must see the user");
        assertEquals(traceId, ON_THE_WAY_OUT.get("/me").get("traceId"));
        assertTrue(log().lines().anyMatch(line -> line.contains("[tid=" + traceId + " uid=user-42]")
                && line.endsWith("handling me")), log());
    }

    @Test
    void aRejectedRequestHasATraceIdButNoUser() throws Exception {
        HttpResponse<String> response = get("/me-denied", null);
        assertEquals(401, response.statusCode());
        assertNotNull(response.headers().firstValue("X-Request-Id").orElse(null));
        assertNotNull(ON_THE_WAY_OUT.get("/me-denied").get("traceId"));
        assertNull(ON_THE_WAY_OUT.get("/me-denied").get("userId"));
    }

    @Test
    void aBadTokenIsRejectedWithoutAUser() throws Exception {
        assertEquals(401, get("/me-bad", "forged").statusCode());
        assertNull(ON_THE_WAY_OUT.get("/me-bad").get("userId"));
    }

    @Test
    void theContainersOwnErrorLineCarriesTheRequest() throws Exception {
        HttpResponse<String> response = get("/boom", "good");
        assertEquals(500, response.statusCode());
        String traceId = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertTrue(log().lines().anyMatch(line -> line.contains("Servlet.service()")
                && line.contains("[tid=" + traceId + " uid=user-42]")), log());

        // The context of the failed request must not leak into the next one on that thread.
        for (int i = 0; i < 20; i++) {
            get("/me-after-" + i, null);
            assertFalse(traceId.equals(ON_THE_WAY_OUT.get("/me-after-" + i).get("traceId")));
            assertNull(ON_THE_WAY_OUT.get("/me-after-" + i).get("userId"));
        }
    }
}
