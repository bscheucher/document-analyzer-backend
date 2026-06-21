/**
 * Inbound HTTP adapter (presentation layer).
 *
 * <p>Controllers, wire DTOs, request validation, the SSE progress notifier, the
 * {@code @Async} analysis launcher, and the global exception handler. May depend
 * only on {@code ..domain..} (ports + model); never on {@code persistence} or
 * {@code integration}.
 *
 * <p>See {@code src/docs/layered-architecture-refactor.md}.
 */
package com.example.docanalyzer.web;
