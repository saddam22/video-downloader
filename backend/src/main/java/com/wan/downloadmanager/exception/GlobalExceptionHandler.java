package com.wan.downloadmanager.exception;

import com.wan.downloadmanager.security.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Uniform error body: {@code {"error": {"code", "message", "field?"}}} (same shape as WAN Identity Hub). */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WdmException.class)
    public ResponseEntity<Map<String, Object>> wdm(WdmException e) {
        return ResponseEntity.status(e.status()).body(body(e.code(), e.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        var fe = e.getBindingResult().getFieldError();
        return ResponseEntity.badRequest().body(body("validation", fe != null ? fe.getDefaultMessage() : "Invalid request",
                fe != null ? fe.getField() : null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(body("malformed_json", "Request body is not valid JSON", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("Unhandled error: {}", Redactor.redact(String.valueOf(e.getMessage())), e);
        return ResponseEntity.internalServerError().body(body("internal", "Unexpected error", null));
    }

    private static Map<String, Object> body(String code, String message, String field) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", Redactor.redact(message));
        if (field != null) err.put("field", field);
        return Map.of("error", err);
    }
}
