package io.github.mrmtd.orchestrator;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** CORS for the React app, and a request id plus one access log line per request. */
@Configuration
class WebConfig implements WebMvcConfigurer {

    static final String REQUEST_ID = "X-Request-Id";

    private final String[] allowedOrigins;

    WebConfig(@Value("${orchestrator.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(REQUEST_ID);
    }

    /**
     * Uses the front end's {@code X-Request-Id} if it's safe, otherwise generates one; puts it in the
     * logging MDC (so every log line for the request carries it) and in the response.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    OncePerRequestFilter requestIdFilter() {
        Logger log = LoggerFactory.getLogger("http");
        Pattern safe = Pattern.compile("[A-Za-z0-9._-]{1,64}");
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String id = request.getHeader(REQUEST_ID);
                if (id == null || !safe.matcher(id).matches()) {
                    id = UUID.randomUUID().toString();
                }
                response.setHeader(REQUEST_ID, id);
                long start = System.nanoTime();
                try (var ignored = MDC.putCloseable("requestId", id)) {
                    chain.doFilter(request, response);
                } finally {
                    if (!request.getRequestURI().startsWith("/actuator")) {
                        log.info("method={} path={} status={} durationMs={}", request.getMethod(),
                                request.getRequestURI(), response.getStatus(),
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    }
                }
            }
        };
    }
}
