package com.example.docanalyzer.domain.service;

/**
 * Tuning for the chunked map-reduce analysis path. Built from configuration in
 * the {@code config} layer and constructor-injected so the domain reads no
 * {@code @Value}.
 *
 * <p>Below {@code thresholdChars} the text is analysed in a single LLM call;
 * above it the text is split into {@code chunkSizeChars} windows overlapping by
 * {@code overlapChars}.
 */
public record ChunkingConfig(
        int thresholdChars,
        int chunkSizeChars,
        int overlapChars
) {}
