package dev.bandeira.prsentinel.agent.application;

import dev.bandeira.prsentinel.agent.application.port.AgentResultPublisher;
import dev.bandeira.prsentinel.agent.application.port.FindingSource;
import dev.bandeira.prsentinel.agent.domain.FindingFilter;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent.Status;
import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent.DiffChunk;
import dev.bandeira.prsentinel.common.event.Finding;
import java.util.ArrayList;
import java.util.List;

/**
 * Analisa os chunks de um diff e publica o resultado do agente.
 *
 * <p>Sempre publica, inclusive quando falha: é o {@code AgentCompletedEvent} que diz ao
 * orchestrator que este agente terminou. Morrer calado deixaria a review esperando até o timeout
 * (ADR-003) — o resultado seria o mesmo, mas minutos depois e sem motivo registrado.
 *
 * <p>Falha em qualquer chunk reprova o agente inteiro, e os achados já coletados são descartados. A
 * alternativa — publicar {@code SUCCESS} com o que deu tempo de analisar — seria pior: o
 * orchestrator leria como review completa e publicaria no PR uma análise que cobriu metade do diff,
 * sem avisar ninguém. {@code FAILED} é um caso que o orchestrator sabe tratar; "completo pela
 * metade" não é.
 */
public class ReviewChunksUseCase {

  private final FindingSource findingSource;
  private final AgentResultPublisher publisher;
  private final FindingFilter filter;
  private final AgentType agentType;

  public ReviewChunksUseCase(
      FindingSource findingSource,
      AgentResultPublisher publisher,
      FindingFilter filter,
      AgentType agentType) {
    this.findingSource = findingSource;
    this.publisher = publisher;
    this.filter = filter;
    this.agentType = agentType;
  }

  public AgentCompletedEvent handle(DiffReadyEvent event) {
    AgentCompletedEvent result;
    try {
      result = success(event, analyze(event.chunks()));
    } catch (RuntimeException e) {
      result = failure(event, e);
    }

    publisher.publish(result);
    return result;
  }

  private List<Finding> analyze(List<DiffChunk> chunks) {
    List<Finding> findings = new ArrayList<>();
    for (DiffChunk chunk : chunks) {
      findings.addAll(filter.apply(findingSource.findIn(chunk)));
    }
    return findings;
  }

  private AgentCompletedEvent success(DiffReadyEvent event, List<Finding> findings) {
    // Usage fica nulo até a fase 7, que é quando tokens, latência e custo entram.
    return new AgentCompletedEvent(
        event.reviewId(), agentType, Status.SUCCESS, List.copyOf(findings), null, null);
  }

  /**
   * O motivo publicado é o tipo da exceção, nunca a mensagem dela.
   *
   * <p>{@code errorMessage} é gravado pelo orchestrator e pode acabar no PR, e a mensagem de um
   * cliente de LLM costuma trazer de volta o corpo da requisição — que é o código do pull request.
   * O detalhe completo vai para o log do agente, que é nosso.
   */
  private AgentCompletedEvent failure(DiffReadyEvent event, RuntimeException cause) {
    return new AgentCompletedEvent(
        event.reviewId(),
        agentType,
        Status.FAILED,
        List.of(),
        null,
        "falha ao analisar o diff: " + cause.getClass().getSimpleName());
  }
}
