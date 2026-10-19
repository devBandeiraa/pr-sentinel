package dev.bandeira.prsentinel.gateway.application.port;

import dev.bandeira.prsentinel.gateway.domain.DeliveryId;
import dev.bandeira.prsentinel.gateway.domain.PullRequestAction;
import java.time.Instant;
import java.util.UUID;

/** Registro durável das entregas já processadas. Implementado em {@code infrastructure}. */
public interface ProcessedDeliveries {

  /**
   * Reserva o delivery id, de forma atômica.
   *
   * <p>A implementação precisa reservar numa única operação — {@code INSERT ... ON CONFLICT DO
   * NOTHING} — e nunca com um {@code SELECT} seguido de {@code INSERT}. Esse padrão é um TOCTOU:
   * duas entregas simultâneas leem "não existe" e ambas publicam, e o teste sequencial não pega.
   *
   * @return {@code true} se esta chamada reservou agora; {@code false} se já estava reservado
   */
  boolean register(DeliveryId id, UUID reviewId, PullRequestAction action);

  /**
   * Remove os registros anteriores ao cutoff.
   *
   * @return quantos registros foram removidos
   */
  int purgeOlderThan(Instant cutoff);
}
