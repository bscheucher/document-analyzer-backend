package com.example.docanalyzer.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Framework-free domain model for an application user. The JPA mapping lives in
 * {@code persistence.entity.UserEntity}; {@code PersistenceMapper} translates.
 */
@Getter
@Setter
public class User {
    private UUID id;
    private String email;
    private Instant createdAt;
}
