package dev.bandeira.prsentinel.orchestrator.application.port;

import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.orchestrator.application.ReviewSnapshot;
import dev.bandeira.prsentinel.orchestrator.domain.ReviewStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Estado durável das reviews. Implementado em {@code infrastructure} sobre o Postgres. */
public interface Reviews {

  /**
   * Registra a review, de forma idempotente.
   *
   * @return {@code true} se esta chamada registrou agora; {@code false} se já existia
   */
  boolean register(ReviewRequestedEvent event);

  /**
   * Grava o resultado de um agente e marca a review como em andamento.
   *
   * <p>A unicidade {@code review_id + agent} é a trava: um resultado repetido não grava de novo nem
   * reabre a contagem. Isso importa porque a fila pode reentregar a mesma mensagem.
   *
   * @return {@code true} se gravou agora; {@code false} se aquele agente já tinha respondido
   * @throws dev.bandeira.prsentinel.orchestrator.application.ReviewNotRegisteredException se a
   *     review ainda não existe
   */
  boolean recordAgentResult(AgentCompletedEvent event);

  Optional<ReviewSnapshot> snapshot(UUID reviewId);

  /**
   * Encerra a review, numa transição condicional.
   *
   * <p>É o ponto que garante publicação única (ADR-008). O {@code UPDATE} só afeta linha quando a
   * review ainda está aberta, então o terceiro resultado de agente e a varredura de timeout podem
   * disputar a mesma review sem gerar dois {@code pr.review.ready} — e dois comentários no mesmo
   * PR. Implementar isso como consulta de status seguida de {@code UPDATE} reintroduz a corrida.
   *
   * @return {@code true} se esta chamada encerrou a review; {@code false} se alguém já havia
   *     encerrado
   */
  boolean close(UUID reviewId, ReviewStatus finalStatus, Verdict verdict, Instant completedAt);

  /** Reviews ainda abertas cujo pedido é anterior ao cutoff. */
  List<UUID> findOpenRequestedBefore(Instant cutoff);
}
