package dev.bandeira.prsentinel.gateway.application;

/**
 * Como uma entrega terminou. O adaptador HTTP traduz isto em status e log.
 *
 * <p>Os três são resultados de sucesso e respondem {@code 202} (ADR-005): responder não-2xx numa
 * duplicata faria o GitHub reentregar e marcar a entrega como falha permanente no painel do App.
 */
public enum DeliveryOutcome {
  /** Reservou o delivery id e publicou o evento. */
  PUBLISHED,
  /** O delivery id já tinha sido processado; nada foi publicado. */
  DUPLICATE,
  /** A ação do PR não dispara review; nada foi registrado nem publicado. */
  IGNORED_ACTION
}
