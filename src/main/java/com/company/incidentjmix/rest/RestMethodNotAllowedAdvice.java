package com.company.incidentjmix.rest;

import com.company.incidentjmix.rest.dto.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 405 for unsupported methods on mapped paths — in particular DELETE on
 * /api/incidents/{id}: deletion is out of MVP scope (TASK.md §2/§8), so no
 * DELETE handler exists and Spring rejects the method during handler
 * mapping. At that point no controller is selected yet, therefore this
 * handler cannot live in the assignableTypes-scoped
 * {@link RestExceptionHandler} and is registered as a separate advice.
 */
@RestControllerAdvice
public class RestMethodNotAllowedAdvice {

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError("Метод " + e.getMethod() + " не поддерживается для этого ресурса"));
    }
}
