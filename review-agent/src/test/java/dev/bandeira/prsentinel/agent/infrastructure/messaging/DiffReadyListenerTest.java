package dev.bandeira.prsentinel.agent.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.bandeira.prsentinel.agent.application.ReviewChunksUseCase;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent.Status;
import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent.DiffChunk;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiffReadyListenerTest {

  private final ReviewChunksUseCase reviewChunks = mock(ReviewChunksUseCase.class);
  private final DiffReadyListener listener = new DiffReadyListener(reviewChunks);

  @Test
  void delegaODiffParaOCasoDeUso() {
    when(reviewChunks.handle(any())).thenReturn(result(Status.SUCCESS, null));

    DiffReadyEvent event = diffReady();
    listener.onDiffReady(event);

    verify(reviewChunks).handle(event);
  }

  @Test
  void resultadoFailedNaoRejeitaAMensagem() {
    // O caso de uso já publicou FAILED. Deixar exceção escapar aqui faria o retry da fila chamar
    // o LLM de novo, pagando outra vez pelo mesmo erro, e no fim mandaria a mensagem para a DLQ
    // sem que o orchestrator soubesse que este agente desistiu.
    when(reviewChunks.handle(any()))
        .thenReturn(result(Status.FAILED, "falha ao analisar o diff: IllegalStateException"));

    assertThatCode(() -> listener.onDiffReady(diffReady())).doesNotThrowAnyException();
  }

  private static AgentCompletedEvent result(Status status, String errorMessage) {
    return new AgentCompletedEvent(
        UUID.randomUUID(), AgentType.SECURITY, status, List.of(), null, errorMessage);
  }

  private static DiffReadyEvent diffReady() {
    return new DiffReadyEvent(
        UUID.randomUUID(),
        new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123"),
        List.of(new DiffChunk("src/A.java", "java", 0, "@@ -1 +1 @@\n+novo")));
  }
}
