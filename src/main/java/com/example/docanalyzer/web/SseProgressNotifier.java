package com.example.docanalyzer.web;

import com.example.docanalyzer.domain.model.AnalysisProgress;
import com.example.docanalyzer.domain.model.DocumentStatus;
import com.example.docanalyzer.domain.port.out.ProgressNotifier;
import com.example.docanalyzer.web.dto.AnalysisProgressEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE-backed {@link ProgressNotifier}. Owns the per-document emitter registry
 * and the {@code SseEmitter} lifecycle, mapping the domain's
 * {@link AnalysisProgress} onto the {@link AnalysisProgressEvent} wire format.
 */
@Slf4j
@Component
public class SseProgressNotifier implements ProgressNotifier {

    private static final long TIMEOUT_MS = 5 * 60 * 1000L; // 5 min

    // Holds active SSE emitters by document ID
    private final ConcurrentHashMap<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * Registers an emitter for {@code documentId}. If the document's analysis is
     * already in a terminal state, the final event is sent immediately and the
     * stream completed. Owner verification is the caller's responsibility.
     */
    public SseEmitter subscribe(UUID documentId, DocumentStatus currentStatus) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        SseEmitter previous = emitters.put(documentId, emitter);
        if (previous != null) {
            // Reconnect: terminate the stale connection cleanly so the old
            // request thread is released instead of waiting for its timeout.
            try {
                previous.complete();
            } catch (Exception ignored) {
                // best-effort: prior emitter may already be in a terminal state
            }
        }

        // remove(key, value) so a late callback from an already-replaced
        // emitter cannot evict the current one from the map.
        emitter.onCompletion(() -> emitters.remove(documentId, emitter));
        emitter.onTimeout(() -> emitters.remove(documentId, emitter));
        emitter.onError(e -> emitters.remove(documentId, emitter));

        if (currentStatus == DocumentStatus.DONE || currentStatus == DocumentStatus.FAILED) {
            publish(documentId, new AnalysisProgress(currentStatus.name(), "Analysis already complete", 100));
            emitter.complete();
        }

        return emitter;
    }

    @Override
    public void publish(UUID documentId, AnalysisProgress progress) {
        SseEmitter emitter = emitters.get(documentId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(new AnalysisProgressEvent(
                            progress.stage(), progress.message(), progress.progressPercent())));
        } catch (IOException | IllegalStateException e) {
            // IOException: client disconnected. IllegalStateException: emitter
            // was completed under us (e.g. replaced by a reconnect).
            log.debug("SSE emitter unavailable for {}: {}", documentId, e.getMessage());
            emitters.remove(documentId, emitter);
        }
    }

    @Override
    public void complete(UUID documentId) {
        SseEmitter emitter = emitters.remove(documentId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }
}
