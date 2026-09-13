package com.pulse.boundary;

import com.helix.api.RuleCompilationException;
import com.helix.api.RuleExecutionException;
import com.pulse.boundary.dto.ProblemDetail;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global JAX-RS exception mapper translating standard and domain exceptions into
 * RFC 7807 problem+json formatted error responses.
 */
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionMapper.class);
    private static final String PROBLEM_JSON = "application/problem+json";

    @Override
    public Response toResponse(Exception exception) {
        log.warn("Mapping exception to problem response: {} - {}", exception.getClass().getSimpleName(), exception.getMessage());

        if (exception instanceof WebApplicationException webAppEx) {
            Response originalResponse = webAppEx.getResponse();
            int statusCode = originalResponse != null ? originalResponse.getStatus() : 500;
            Response.StatusType statusType = originalResponse != null ? originalResponse.getStatusInfo() : Response.Status.INTERNAL_SERVER_ERROR;
            String reason = statusType != null ? statusType.getReasonPhrase() : "HTTP Error";
            return createProblemResponse(statusCode, reason, webAppEx.getMessage());
        }

        if (exception instanceof RuleCompilationException compilationEx) {
            return createProblemResponse(400, "Bad Request", compilationEx.getMessage());
        }

        if (exception instanceof RuleExecutionException executionEx) {
            return createProblemResponse(422, "Unprocessable Entity", executionEx.getMessage());
        }

        if (exception instanceof EntityNotFoundException notFoundEx) {
            return createProblemResponse(404, "Not Found", notFoundEx.getMessage());
        }

        if (exception instanceof OptimisticLockException lockEx) {
            return createProblemResponse(409, "Conflict", "Optimistic lock conflict: " + lockEx.getMessage());
        }

        if (exception instanceof IllegalArgumentException badReqEx) {
            return createProblemResponse(400, "Bad Request", badReqEx.getMessage());
        }

        // Generic fallback error mapping
        log.error("Unhandled server exception caught by GlobalExceptionMapper", exception);
        return createProblemResponse(500, "Internal Server Error", "An unexpected server error occurred.");
    }

    private Response createProblemResponse(int status, String title, String detail) {
        ProblemDetail problem = new ProblemDetail(status, title, detail != null ? detail : title);
        return Response.status(status)
                .type(PROBLEM_JSON)
                .entity(problem)
                .build();
    }
}
