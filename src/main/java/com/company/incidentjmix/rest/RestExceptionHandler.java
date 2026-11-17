package com.company.incidentjmix.rest;

import com.company.incidentjmix.rest.dto.ApiError;
import com.company.incidentjmix.service.ConflictException;
import com.company.incidentjmix.service.NotFoundException;
import com.company.incidentjmix.service.UnknownUserException;
import com.company.incidentjmix.service.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps domain and transport exceptions of the /api/** controllers to the
 * uniform {"detail": "..."} error body (TASK.md §3, ADR-002). Scoped to the
 * REST controllers so Jmix/Vaadin endpoints keep their own error handling.
 * <p>
 * 405 (method not supported) is handled by {@link RestMethodNotAllowedAdvice}:
 * that exception is thrown during handler mapping, before a controller is
 * selected, so an assignableTypes-scoped advice cannot see it.
 */
@RestControllerAdvice(assignableTypes = {
        IncidentController.class, DashboardController.class, UserController.class})
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> validation(ValidationException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "Некорректное тело запроса: ожидается JSON");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "Некорректное значение параметра: " + e.getName());
    }

    @ExceptionHandler(UnknownUserException.class)
    public ResponseEntity<ApiError> unknownUser(UnknownUserException e) {
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> optimisticLock(ObjectOptimisticLockingFailureException e) {
        return error(HttpStatus.CONFLICT, "Данные были изменены другим пользователем, повторите запрос");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unexpected REST API error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка сервера");
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(new ApiError(detail));
    }
}
