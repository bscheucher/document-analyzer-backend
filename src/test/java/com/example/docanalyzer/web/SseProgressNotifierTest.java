package com.example.docanalyzer.web;

import com.example.docanalyzer.domain.model.DocumentStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SseProgressNotifierTest {

    private final SseProgressNotifier notifier = new SseProgressNotifier();

    @Test
    void subscribe_returnsEmitter_forInProgressDocument() {
        var emitter = notifier.subscribe(UUID.randomUUID(), DocumentStatus.PENDING);
        assertThat(emitter).isNotNull();
    }

    @Test
    void subscribe_reconnect_completesPriorEmitter() {
        UUID id = UUID.randomUUID();

        var first = notifier.subscribe(id, DocumentStatus.PENDING);
        var second = notifier.subscribe(id, DocumentStatus.PENDING);

        // The first emitter must be in a terminal state — sending on a
        // completed SseEmitter throws IllegalStateException.
        assertThat(first).isNotSameAs(second);
        assertThatThrownBy(() -> first.send("late"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void subscribe_terminalStatus_completesImmediately() {
        UUID id = UUID.randomUUID();

        var emitter = notifier.subscribe(id, DocumentStatus.DONE);

        // Already completed: a further send must fail.
        assertThatThrownBy(() -> emitter.send("late"))
                .isInstanceOf(IllegalStateException.class);
    }
}
