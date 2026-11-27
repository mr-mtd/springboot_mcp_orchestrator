package io.github.mrmtd.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * CORS for the React front end. Origins are exact matches; patterns such as
 * {@code https://*.example.com} go in {@code allowedOriginPatterns}.
 */
@ConfigurationProperties("orchestrator.cors")
public record CorsProperties(
        @DefaultValue({"http://localhost:3000", "http://localhost:5173"}) List<String> allowedOrigins,
        @DefaultValue({}) List<String> allowedOriginPatterns,
        @DefaultValue({"GET", "POST", "OPTIONS"}) List<String> allowedMethods,
        @DefaultValue("false") boolean allowCredentials,
        @DefaultValue("1h") Duration maxAge) {
}
