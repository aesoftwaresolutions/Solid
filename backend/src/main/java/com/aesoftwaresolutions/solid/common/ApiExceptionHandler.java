package com.aesoftwaresolutions.solid.common;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts exceptions into RFC 9457 problem details so every API error has the same shape. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return problem(HttpStatus.BAD_REQUEST, "File too large", "That file is larger than this server accepts");
    }

    @ExceptionHandler(ForbiddenException.class)
    ProblemDetail forbidden(ForbiddenException e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
    }

    @ExceptionHandler(ApiProblemException.class)
    ProblemDetail apiProblem(ApiProblemException e) {
        HttpStatus status = HttpStatus.valueOf(e.status());
        ProblemDetail pd = problem(status, status.getReasonPhrase(), e.getMessage());
        pd.setProperty("code", e.code());
        return pd;
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException e) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "Business rule violated", e.getMessage());
        pd.setProperty("code", e.code());
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request body", "The request body is missing or malformed");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid");
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        pd.setProperty("errors", errors);
        return pd;
    }

    /**
     * Database constraints and triggers are a safety net behind the application checks. If one fires,
     * report a 409 instead of a 500, without leaking SQL details.
     */
    @ExceptionHandler({DataAccessException.class, TransactionSystemException.class})
    ProblemDetail database(RuntimeException e) {
        String state = sqlState(e);
        if (state != null && (state.startsWith("23") || state.equals("P0001"))) {
            ProblemDetail pd = problem(HttpStatus.CONFLICT, "Business rule violated",
                    "The change conflicts with existing data or a bookkeeping rule");
            pd.setProperty("code", state.equals("P0001") ? "RULE_VIOLATION" : "CONSTRAINT_VIOLATION");
            return pd;
        }
        throw e;
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
