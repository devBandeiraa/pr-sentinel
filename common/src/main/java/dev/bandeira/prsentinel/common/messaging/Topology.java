package dev.bandeira.prsentinel.common.messaging;

/**
 * Nomes de exchange e routing keys. Centralizados aqui para que produtores
 * e consumidores nunca divirjam.
 *
 * Fluxo:
 *   pr.review.requested -> diff-fetcher
 *   pr.diff.ready       -> review-agent (uma fila por tipo de agente)
 *   pr.agent.completed  -> orchestrator
 *   pr.review.ready     -> github-publisher
 */
public final class Topology {

    public static final String EXCHANGE = "pr-sentinel.events";
    public static final String DLX = "pr-sentinel.dlx";

    public static final String RK_REVIEW_REQUESTED = "pr.review.requested";
    public static final String RK_DIFF_READY = "pr.diff.ready";
    public static final String RK_AGENT_COMPLETED = "pr.agent.completed";
    public static final String RK_REVIEW_READY = "pr.review.ready";

    public static final String CORRELATION_HEADER = "x-correlation-id";

    private Topology() {
    }

    public static String queue(String service) {
        return "pr-sentinel." + service;
    }

    public static String dlq(String service) {
        return queue(service) + ".dlq";
    }
}
