package com.notification.api;

import com.notification.api.dto.ErrorResponse;
import com.notification.application.ValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.List;
import java.util.stream.Collectors;

@Provider
public class ValidationExceptionMapper implements ExceptionMapper<Exception> {

    @Override
    public Response toResponse(Exception exception) {
        if (exception instanceof ValidationException ve) {
            ErrorResponse error = new ErrorResponse(ve.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(error)
                    .build();
        }

        if (exception instanceof ConstraintViolationException cve) {
            List<String> details = cve.getConstraintViolations().stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.toList());
            String message = details.isEmpty() ? "Validation failed" : details.get(0);
            ErrorResponse error = new ErrorResponse(message, details);
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(error)
                    .build();
        }

        if (exception instanceof IllegalArgumentException iae) {
            ErrorResponse error = new ErrorResponse(iae.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(error)
                    .build();
        }

        // Jackson / JSON parse error
        if (exception.getClass().getName().contains("Json") || exception.getMessage() != null && exception.getMessage().contains("JSON")) {
            ErrorResponse error = new ErrorResponse("Invalid JSON payload: " + exception.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(error)
                    .build();
        }

        // Other internal errors
        ErrorResponse error = new ErrorResponse("Internal server error: " + exception.getMessage());
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(error)
                .build();
    }
}
