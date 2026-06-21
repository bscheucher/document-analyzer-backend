package com.example.docanalyzer.config;

import com.example.docanalyzer.domain.port.out.DocumentRepositoryPort;
import com.example.docanalyzer.domain.port.out.LlmPort;
import com.example.docanalyzer.domain.port.out.ProgressNotifier;
import com.example.docanalyzer.domain.port.out.StoragePort;
import com.example.docanalyzer.domain.port.out.TextExtractorPort;
import com.example.docanalyzer.domain.service.ChunkingConfig;
import com.example.docanalyzer.domain.service.DocumentAnalysisService;
import com.example.docanalyzer.domain.service.LlmResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free domain services into the Spring context, injecting
 * the adapter beans (resolved by their port type) and turning {@code @Value}
 * configuration into plain domain config objects.
 */
@Configuration
class DomainConfig {

    @Bean
    DocumentAnalysisService documentAnalysisService(
            DocumentRepositoryPort documentRepository,
            StoragePort storagePort,
            LlmPort llmPort,
            TextExtractorPort textExtractorPort,
            ProgressNotifier progressNotifier,
            ObjectMapper objectMapper,
            @Value("${app.ai.chunking.threshold-chars:20000}") int thresholdChars,
            @Value("${app.ai.chunking.chunk-size-chars:6000}") int chunkSizeChars,
            @Value("${app.ai.chunking.overlap-chars:200}") int overlapChars) {

        ChunkingConfig chunking = new ChunkingConfig(thresholdChars, chunkSizeChars, overlapChars);
        return new DocumentAnalysisService(
                documentRepository, storagePort, llmPort, textExtractorPort,
                progressNotifier, chunking, new LlmResponseParser(objectMapper));
    }
}
