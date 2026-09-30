package com.anvarkangadiyil.rewards.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.anvarkangadiyil.rewards.ledger.LedgerExceptions.AccountNotFound;
import com.anvarkangadiyil.rewards.ledger.LedgerExceptions.IdempotencyConflict;
import com.anvarkangadiyil.rewards.ledger.LedgerExceptions.InsufficientPoints;

import jakarta.validation.ConstraintViolationException;

/** Maps domain failures to RFC 7807 problem responses. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(AccountNotFound.class)
    ProblemDetail notFound(AccountNotFound e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InsufficientPoints.class)
    ProblemDetail insufficient(InsufficientPoints e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setProperty("available", e.available());
        problem.setProperty("requested", e.requested());
        return problem;
    }

    @ExceptionHandler(IdempotencyConflict.class)
    ProblemDetail conflict(IdempotencyConflict e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    // Reached when the same idempotency key is sent for two different customers at the same moment.
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "request conflicts with an existing entry");
    }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class, MissingRequestHeaderException.class})
    ProblemDetail badRequest(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
