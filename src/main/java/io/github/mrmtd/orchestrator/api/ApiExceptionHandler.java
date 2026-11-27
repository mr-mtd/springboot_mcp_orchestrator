package io.github.mrmtd.orchestrator.api;

import io.github.mrmtd.orchestrator.mcp.McpToolException;
import io.github.mrmtd.orchestrator.mcp.McpUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

/**
 * Errors as RFC 9457 problem details, which the front end can show directly:
 * unknown load or carrier 404, bad input 400, MCP rate limit 429 with {@code Retry-After},
 * MCP server down 503, anything else 500. Spring's own errors (validation, type mismatch, missing
 * parameter) are handled by {@link ResponseEntityExceptionHandler} in the same format.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(McpToolException.class)
    ResponseEntity<ProblemDetail> toolError(McpToolException e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
        };
        ProblemDetail body = problem(status, e.getMessage(), e.kind().outcome());
        body.setProperty("tool", e.tool());
        var response = ResponseEntity.status(status);
        if (e.kind() == McpToolException.Kind.RATE_LIMITED) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        }
        return response.body(body);
    }

    @ExceptionHandler(McpUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable(McpUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE,
                        "The transport data service is unavailable. Try again shortly.", "mcp_unavailable"));
    }

    @ExceptionHandler(RuntimeException.class)
    ProblemDetail unexpected(RuntimeException e) {
        log.error("Unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", "error");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String code) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("urn:problem:orchestrator:" + code));
        body.setProperty("code", code);
        return body;
    }
}
