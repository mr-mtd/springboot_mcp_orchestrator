package io.github.mrmtd.orchestrator.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Gives every request a correlation id and writes one access log line when it finishes.
 *
 * <p>The id comes from the {@code X-Request-Id} header if the front end sent a sane one, otherwise
 * it's generated. It goes into the logging MDC as {@code requestId}, so every log line for the
 * request (including the MCP calls it makes, also on fan-out threads) carries it, and back to the
 * client in the response header. Request counts and durations as metrics come from Spring's own
 * {@code http.server.requests}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long start = System.nanoTime();
        try (var ignored = MDC.putCloseable(MDC_KEY, requestId)) {
            try {
                chain.doFilter(request, response);
            } finally {
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                String path = request.getRequestURI();
                int status = response.getStatus();
                if (path.startsWith("/actuator")) {
                    log.debug("http method={} path={} status={} durationMs={}", request.getMethod(), path, status, ms);
                } else if (status >= 500) {
                    log.warn("http method={} path={} status={} durationMs={}", request.getMethod(), path, status, ms);
                } else {
                    log.info("http method={} path={} status={} durationMs={} origin={}",
                            request.getMethod(), path, status, ms, request.getHeader("Origin"));
                }
            }
        }
    }
}
