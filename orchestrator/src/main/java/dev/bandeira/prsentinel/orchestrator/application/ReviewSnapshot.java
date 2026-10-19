package dev.bandeira.prsentinel.orchestrator.application;

import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * O estado de uma review como a consolidação precisa dele: qual PR, quem respondeu, o que acharam e
 * quando tudo começou.
 *
 * <p>É modelo de leitura, montado de uma vez pela porta. Buscar essas quatro coisas em chamadas
 * separadas abriria janela para consolidar com dados de instantes diferentes.
 *
 * @param findings todos os achados gravados, antes da deduplicação da ADR-008
 * @param requestedAt instante do pedido, usado para calcular a duração que vai no evento
 */
public record ReviewSnapshot(
    UUID reviewId,
    PullRequestRef pullRequest,
    Set<AgentType> respondedAgents,
    List<Finding> findings,
    Instant requestedAt) {}
