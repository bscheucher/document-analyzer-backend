package com.example.docanalyzer.domain.port.out;

import com.example.docanalyzer.domain.model.User;

import java.util.Optional;

/**
 * Outbound port for user persistence. Implemented by
 * {@code persistence.UserRepositoryAdapter}.
 */
public interface UserRepositoryPort {

    Optional<User> findByEmail(String email);

    User save(User user);
}
