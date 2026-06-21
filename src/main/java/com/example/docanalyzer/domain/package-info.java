/**
 * Application core (hexagon). Framework-free.
 *
 * <p>Classes under {@code ..domain..} must not depend on Spring, JPA, the Servlet
 * API, PDFBox, or Reactor. Permitted dependencies are the JDK, Jackson
 * ({@code com.fasterxml.jackson..}), and the SLF4J API. This invariant is the
 * reason the core is unit-testable without a Spring context; it is enforced by
 * {@code LayeredArchitectureTest}.
 *
 * <p>See {@code src/docs/layered-architecture-refactor.md}.
 */
package com.example.docanalyzer.domain;
