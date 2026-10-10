package io.adaptiveagents.ingress.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * RFC 7807 error mapping for the REST ingress. Every error body leaves the API as
 * {@code application/problem+json}.
 *
 * <p>Mapping table: 400 for {@link TaskValidationException} (semantic envelope validation and a
 * non-numeric {@code Last-Event-ID}), for {@link HttpMessageNotReadableException} (Jackson bind
 * failures: malformed JSON, bad UUID, unknown {@code forced_topology} enum value) and for
 * {@link MethodArgumentTypeMismatchException} (malformed path variable); 404 for
 * {@link TaskNotFoundException}. 401 is NOT mapped here: {@link ApiKeyAuthFilter} writes its
 * 401 problem-details body directly (documented single path).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TaskValidationException.class)
    public ResponseEntity<ProblemDetail> invalidTask(TaskValidationException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid task envelope", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadableBody(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request body", rootMessage(e));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request parameter",
                "Parameter '" + e.getName() + "' has an invalid value");
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ProblemDetail> unknownTask(TaskNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Unknown task", e.getMessage());
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /** Most-specific human-readable cause message, avoiding raw Jackson exception toString noise. */
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? "Unreadable request body" : message;
    }
}
