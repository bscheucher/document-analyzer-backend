package com.example.docanalyzer.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

// Maps oversized multipart uploads to 413 with a friendly body.
//
// This lives in its own advice rather than in GlobalExceptionHandler because
// that class extends ResponseEntityExceptionHandler, whose handleException
// already maps MaxUploadSizeExceededException — two @ExceptionHandler methods
// for the same type in one class is an ambiguous (fatal) mapping. As a
// separate, highest-precedence advice this handler wins by ordering, with no
// ambiguity.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class UploadSizeExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handlePayloadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "File exceeds the maximum allowed size"));
    }
}
