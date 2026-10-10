package io.adaptiveagents.ingress.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fail-closed API-key authentication for the REST ingress: every guarded request must carry an
 * {@code X-API-Key} header equal to the {@code runtime.api-key} property. The key is AUTH ONLY
 * (docs/ENVELOPE.md) — it carries no identity or topology. No spring-security, no basic auth,
 * no OAuth: this plain filter is the whole auth story.
 *
 * <p>Fail-closed: a blank/missing {@code runtime.api-key} property rejects every guarded
 * request with 401 (an unconfigured ingress must never accept traffic); so do a missing or
 * mismatched header. Comparison is constant-time ({@link MessageDigest#isEqual}).
 *
 * <p>Guarded surface — T23 seam: {@link #GUARDED_PREFIXES} is the single table-driven list of
 * path prefixes under auth. A later ingress (T23 MCP) exempts itself by mounting under a
 * prefix absent from this list (e.g. {@code /mcp/**}); adding a new guarded surface is a
 * one-line change here. Prefix matching is segment-aware: a prefix matches the exact path or
 * any sub-path below it ({@code /tasks} matches {@code /tasks} and {@code /tasks/...}, not
 * {@code /tasksfoo}).
 *
 * <p>401 responses are written HERE, directly (chosen single path — not thrown for the
 * {@link ApiExceptionHandler} to map), as an RFC 7807 {@code application/problem+json} body.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** Path prefixes this filter guards. T23 seam: exempt prefixes (e.g. {@code /mcp}) never appear here. */
    static final List<String> GUARDED_PREFIXES = List.of("/tasks");

    private static final String API_KEY_HEADER = "X-API-Key";

    private final String expectedApiKey;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(@Value("${runtime.api-key:}") String expectedApiKey, ObjectMapper objectMapper) {
        this.expectedApiKey = expectedApiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isGuarded(request)) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(API_KEY_HEADER);
        if (!authorized(provided)) {
            writeUnauthorized(response, provided == null
                    ? "Missing " + API_KEY_HEADER + " header"
                    : "Invalid " + API_KEY_HEADER + " header");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isGuarded(HttpServletRequest request) {
        String path = request.getRequestURI();
        return GUARDED_PREFIXES.stream().anyMatch(prefix ->
                path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    /** Fail-closed: unconfigured key, missing header or mismatch all reject. */
    private boolean authorized(String provided) {
        if (expectedApiKey == null || expectedApiKey.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedApiKey.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorized(HttpServletResponse response, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setTitle("Unauthorized");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
