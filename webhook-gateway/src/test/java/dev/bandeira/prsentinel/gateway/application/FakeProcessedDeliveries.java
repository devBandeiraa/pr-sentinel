package dev.bandeira.prsentinel.gateway.application;

import dev.bandeira.prsentinel.gateway.application.port.ProcessedDeliveries;
import dev.bandeira.prsentinel.gateway.domain.DeliveryId;
import dev.bandeira.prsentinel.gateway.domain.PullRequestAction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fake de {@link ProcessedDeliveries} usado pelos testes de {@link RequestReviewUseCase} e {@link
 * PurgeProcessedDeliveriesUseCase}. Reproduz a semântica de "reserva" real (um {@code delivery_id}
 * só reserva uma vez) sem depender de banco, e grava os argumentos recebidos para que os testes
 * possam inspecioná-los.
 */
final class FakeProcessedDeliveries implements ProcessedDeliveries {

  private final Set<String> reserved = new HashSet<>();
  private final List<RegisterCall> registerCalls = new ArrayList<>();
  private final List<Instant> purgeCutoffs = new ArrayList<>();
  private int purgeResult;

  record RegisterCall(DeliveryId deliveryId, UUID reviewId, PullRequestAction action) {}

  /** Simula um delivery_id já reservado por uma entrega anterior. */
  void seedAlreadyRegistered(String deliveryIdValue) {
    reserved.add(deliveryIdValue);
  }

  void purgeResultOf(int count) {
    this.purgeResult = count;
  }

  List<RegisterCall> registerCalls() {
    return List.copyOf(registerCalls);
  }

  List<Instant> purgeCutoffs() {
    return List.copyOf(purgeCutoffs);
  }

  @Override
  public boolean register(DeliveryId id, UUID reviewId, PullRequestAction action) {
    registerCalls.add(new RegisterCall(id, reviewId, action));
    return reserved.add(id.value());
  }

  @Override
  public int purgeOlderThan(Instant cutoff) {
    purgeCutoffs.add(cutoff);
    return purgeResult;
  }
}
