package com.example.payment.controller;

import com.example.payment.exception.CurrencyMismatchException;
import com.example.payment.exception.IdempotencyConflictException;
import com.example.payment.exception.IllegalStateTransitionException;
import com.example.payment.exception.InsufficientFundsException;
import com.example.payment.exception.InvalidDepositException;
import com.example.payment.exception.NotFoundException;
import com.example.payment.exception.VendorUnavailableException;
import com.example.payment.vendor.VendorIntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to RFC 9457 problem details. Validation errors (400) are
 * handled by Spring MVC itself once {@code spring.mvc.problemdetails.enabled} is set.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    ProblemDetail illegalTransition(IllegalStateTransitionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ProblemDetail currencyMismatch(CurrencyMismatchException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InvalidDepositException.class)
    ProblemDetail invalidDeposit(InvalidDepositException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(VendorUnavailableException.class)
    ResponseEntity<ProblemDetail> vendorUnavailable(VendorUnavailableException e) {
        var response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
        if (e.retryAfter() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, e.retryAfter().toSeconds())));
        }
        return response.body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()));
    }

    /** Our integration with the vendor is broken: log the details, don't leak them to the client. */
    @ExceptionHandler(VendorIntegrationException.class)
    ProblemDetail vendorIntegration(VendorIntegrationException e) {
        log.error("vendor integration failure", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Vendor integration failure");
    }
}
