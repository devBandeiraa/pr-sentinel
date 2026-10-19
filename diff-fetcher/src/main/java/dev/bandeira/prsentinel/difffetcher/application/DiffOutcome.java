package dev.bandeira.prsentinel.difffetcher.application;

/**
 * Resumo de uma busca de diff, para log e, na fase 7, para métrica.
 *
 * <p>A diferença entre {@code filesFetched} e {@code filesReviewed} é o que o filtro economizou, e
 * {@code chunks} é o número que multiplica por três o custo de LLM da review (ADR-001).
 */
public record DiffOutcome(int filesFetched, int filesReviewed, int chunks) {}
