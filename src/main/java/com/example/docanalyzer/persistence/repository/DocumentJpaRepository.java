package com.example.docanalyzer.persistence.repository;

import com.example.docanalyzer.persistence.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentJpaRepository extends JpaRepository<DocumentEntity, UUID> {

    // Eager-fetch analysis result to avoid N+1 on list view, scoped to owner.
    @Query("SELECT d FROM Document d LEFT JOIN FETCH d.analysisResult " +
            "WHERE d.owner.id = :ownerId " +
            "ORDER BY d.createdAt DESC")
    List<DocumentEntity> findAllByOwnerWithResults(UUID ownerId);

    @Query("SELECT d FROM Document d LEFT JOIN FETCH d.analysisResult " +
            "WHERE d.id = :id AND d.owner.id = :ownerId")
    Optional<DocumentEntity> findByIdAndOwnerWithResult(UUID id, UUID ownerId);

    // Owner-scoped lookup without the analysis-result fetch — used for
    // delete and other writes that don't need the result loaded.
    @Query("SELECT d FROM Document d WHERE d.id = :id AND d.owner.id = :ownerId")
    Optional<DocumentEntity> findByIdAndOwner(UUID id, UUID ownerId);
}
