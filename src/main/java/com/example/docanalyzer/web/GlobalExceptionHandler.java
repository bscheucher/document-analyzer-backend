package com.example.docanalyzer.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

// Extends ResponseEntityExceptionHandler so Spring's built-in web exceptions
// (404, 405, validation, multipart parse errors, etc.) keep their proper
// status codes instead of being collapsed into a generic 500.
//
// MaxUploadSizeExceededException is intentionally NOT handled here: the base
// ResponseEntityExceptionHandler already maps it via handleException, so adding
// a second @ExceptionHandler for it in this class makes the resolver an
// ambiguous mapping and the MVC context fails to start. The 413 customization
// lives in UploadSizeExceptionHandler, a higher-precedence advice that wins by
// ordering instead.
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
