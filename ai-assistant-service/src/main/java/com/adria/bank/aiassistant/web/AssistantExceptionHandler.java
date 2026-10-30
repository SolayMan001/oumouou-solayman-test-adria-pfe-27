package com.adria.bank.aiassistant.web;

import com.adria.bank.aiassistant.service.AssistantException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice
public class AssistantExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(AssistantException.class)
    public ProblemDetail assistant(AssistantException error) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.getStatus()), error.getMessage());
        problem.setProperty("code", error.getCode());
        return problem;
    }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.TransactionException.class})
    public ProblemDetail persistence(RuntimeException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Confirmation storage is unavailable. Retain the existing confirmation ID and idempotency key for reconciliation.");
    }
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException error,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(error, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Message is required and must contain between 1 and 4000 characters."), headers, status, request);
    }
}
