package com.example.docanalyzer.repository;

import com.example.docanalyzer.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    // Eager-fetch analysis result to avoid N+1 on list view
    @Query("SELECT d FROM Document d LEFT JOIN FETCH d.analysisResult ORDER BY d.createdAt DESC")
    List<Document> findAllWithResults();

    @Query("SELECT d FROM Document d LEFT JOIN FETCH d.analysisResult WHERE d.id = :id")
    Optional<Document> findByIdWithResult(UUID id);
}
