package io.github.mrmtd.orchestrator.config;

import io.github.mrmtd.orchestrator.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS for {@code /api/**}, so the React app (on another origin) can call the orchestrator. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private final CorsProperties cors;

    public WebConfig(CorsProperties cors) {
        this.cors = cors;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        log.info("CORS for /api/** allowedOrigins={} allowedOriginPatterns={} methods={}",
                cors.allowedOrigins(), cors.allowedOriginPatterns(), cors.allowedMethods());
        registry.addMapping("/api/**")
                .allowedOrigins(nonBlank(cors.allowedOrigins()))
                .allowedOriginPatterns(nonBlank(cors.allowedOriginPatterns()))
                .allowedMethods(cors.allowedMethods().toArray(String[]::new))
                .allowedHeaders("*")
                // Let the browser read the correlation id and the 429 back-off.
                .exposedHeaders(CorrelationIdFilter.REQUEST_ID_HEADER, HttpHeaders.RETRY_AFTER)
                .allowCredentials(cors.allowCredentials())
                .maxAge(cors.maxAge().toSeconds());
    }

    /** An empty env var (e.g. {@code CORS_ALLOWED_ORIGIN_PATTERNS=}) binds as one blank entry. */
    private static String[] nonBlank(java.util.List<String> values) {
        return values.stream().map(String::trim).filter(v -> !v.isEmpty()).toArray(String[]::new);
    }
}
