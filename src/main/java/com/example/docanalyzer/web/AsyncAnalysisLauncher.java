package com.example.docanalyzer.web;

import com.example.docanalyzer.domain.port.in.DocumentAnalysisUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Runs the (synchronous) analysis use case on the {@code analysisExecutor}
 * thread pool. Keeping the {@code @Async} hop here lets the domain pipeline stay
 * single-threaded and framework-free.
 */
@Component
@RequiredArgsConstructor
public class AsyncAnalysisLauncher {

    private final DocumentAnalysisUseCase analysisService;

    @Async("analysisExecutor")
    public void launch(UUID documentId) {
        analysisService.analyze(documentId);
    }
}
