package com.example.docanalyzer.persistence;

import com.example.docanalyzer.domain.model.User;
import com.example.docanalyzer.domain.port.out.UserRepositoryPort;
import com.example.docanalyzer.persistence.entity.UserEntity;
import com.example.docanalyzer.persistence.mapper.PersistenceMapper;
import com.example.docanalyzer.persistence.repository.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * {@link UserRepositoryPort} backed by Spring Data JPA.
 */
@Service
@RequiredArgsConstructor
public class UserRepositoryAdapter implements UserRepositoryPort {

    private final UserJpaRepository users;
    private final PersistenceMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return users.findByEmail(email).map(mapper::toDomain);
    }

    @Override
    @Transactional
    public User save(User user) {
        UserEntity entity = new UserEntity();
        entity.setEmail(user.getEmail());
        return mapper.toDomain(users.save(entity));
    }
}
