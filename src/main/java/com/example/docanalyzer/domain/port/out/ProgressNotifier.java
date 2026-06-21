package com.example.docanalyzer.domain.port.out;

import com.example.docanalyzer.domain.model.AnalysisProgress;

import java.util.UUID;

/**
 * Outbound port for emitting analysis progress to an interested subscriber.
 * Implemented by {@code web.SseProgressNotifier}; the domain never sees SSE.
 */
public interface ProgressNotifier {

    /** Publish a progress update for the given document (no-op if nobody is listening). */
    void publish(UUID documentId, AnalysisProgress progress);

    /** Signal that no further progress will be emitted for this document. */
    void complete(UUID documentId);
}
