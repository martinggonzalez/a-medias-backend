package com.amedias.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

/** AJUSTAR el formato de error al contrato si el front espera otra forma. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ErrorBody(int status, String error, String message, Map<String, String> fields) {}

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "Datos no válidos", fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> badRequest(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "Petición mal formada", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorBody> invalid(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage(), null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> tooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "La foto supera el máximo de 8 MB", null);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorBody> status(ResponseStatusException e) {
        HttpStatusCode code = e.getStatusCode();
        String msg = e.getReason() != null ? e.getReason() : HttpStatus.valueOf(code.value()).getReasonPhrase();
        return ResponseEntity.status(code).body(new ErrorBody(code.value(), msg, msg, null));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorBody> database(DataAccessException e) {
        log.error("Error de base de datos", e);
        return body(HttpStatus.SERVICE_UNAVAILABLE, "Base de datos no disponible, inténtalo de nuevo", null);
    }

    private ResponseEntity<ErrorBody> body(HttpStatus status, String message, Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ErrorBody(status.value(), message, message, fields));
    }
}
