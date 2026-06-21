/**
 * Outbound persistence adapter (Spring Data JPA).
 *
 * <p>JPA entities, Spring Data repositories, port implementations (where the
 * {@code @Transactional} boundaries live), and the entity&lt;-&gt;domain mapper.
 * May depend only on {@code ..domain..}; never on {@code web} or
 * {@code integration}.
 */
package com.example.docanalyzer.persistence;
