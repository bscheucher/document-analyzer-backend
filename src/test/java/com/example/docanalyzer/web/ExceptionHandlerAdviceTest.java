package com.example.docanalyzer.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pins the @ControllerAdvice exception mappings.
 *
 * <p>Building an {@link ExceptionHandlerMethodResolver} is exactly what the MVC
 * infrastructure does at startup for every advice bean — so these no-context
 * tests catch the "Ambiguous @ExceptionHandler" failure that would otherwise
 * only surface when the full servlet container initializes.
 */
class ExceptionHandlerAdviceTest {

    // Regression: GlobalExceptionHandler extends ResponseEntityExceptionHandler,
    // whose handleException already maps MaxUploadSizeExceededException. Declaring
    // a second @ExceptionHandler for that type in the same class makes this
    // constructor throw "Ambiguous @ExceptionHandler method mapped ...".
    @Test
    void globalExceptionHandler_buildsWithoutAmbiguousMapping() {
        assertThatCode(() -> new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class))
                .doesNotThrowAnyException();
    }

    @Test
    void globalExceptionHandler_mapsIllegalArgumentToBadRequestHandler() {
        ExceptionHandlerMethodResolver resolver =
                new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);
        Method method = resolver.resolveMethod(new IllegalArgumentException("x"));
        assertThat(method).isNotNull();
        assertThat(method.getName()).isEqualTo("handleBadRequest");
    }

    @Test
    void uploadSizeExceptionHandler_mapsOversizedUploadTo413Handler() {
        ExceptionHandlerMethodResolver resolver =
                new ExceptionHandlerMethodResolver(UploadSizeExceptionHandler.class);
        Method method = resolver.resolveMethod(new MaxUploadSizeExceededException(1L));
        assertThat(method).isNotNull();
        assertThat(method.getName()).isEqualTo("handlePayloadTooLarge");
    }
}
