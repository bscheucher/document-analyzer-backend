package com.example.docanalyzer.service;

import com.example.docanalyzer.entity.User;
import com.example.docanalyzer.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the user that owns the current request.
 *
 * <p>Today this is always the configured default user — there is no login
 * flow yet. The point of this abstraction is that the rest of the app
 * already routes ownership decisions through it, so swapping the
 * implementation for one that reads from {@code SecurityContextHolder}
 * once real auth lands is a single-class change.
 */
@Slf4j
@Service
public class CurrentUserProvider {

    private final UserRepository userRepository;
    private final String defaultUserEmail;

    public CurrentUserProvider(
            UserRepository userRepository,
            @Value("${app.auth.default-user-email}") String defaultUserEmail) {
        this.userRepository = userRepository;
        this.defaultUserEmail = defaultUserEmail;
    }

    @PostConstruct
    @Transactional
    void ensureDefaultUserExists() {
        if (userRepository.findByEmail(defaultUserEmail).isEmpty()) {
            User user = new User();
            user.setEmail(defaultUserEmail);
            userRepository.save(user);
            log.info("Created default user: {}", defaultUserEmail);
        }
    }

    public User getCurrentUser() {
        return userRepository.findByEmail(defaultUserEmail)
                .orElseThrow(() -> new IllegalStateException(
                        "Default user not found: " + defaultUserEmail));
    }
}
