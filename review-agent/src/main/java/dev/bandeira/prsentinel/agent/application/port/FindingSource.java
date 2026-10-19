package dev.bandeira.prsentinel.agent.application.port;

import dev.bandeira.prsentinel.common.event.DiffReadyEvent.DiffChunk;
import dev.bandeira.prsentinel.common.event.Finding;
import java.util.List;

/**
 * Origem dos achados de um chunk. Implementado em {@code infrastructure} sobre o LLM.
 *
 * <p>Existe para que o caso de uso não conheça Spring AI nem prompt: o que ele precisa saber é que
 * alguém olha um chunk e devolve achados, e que isso pode falhar.
 *
 * <p>Falha deve lançar. O caso de uso traduz a exceção em {@code AgentCompletedEvent} com status
 * {@code FAILED}, que é o que permite ao orchestrator fechar uma review parcial (ADR-003).
 */
public interface FindingSource {

  List<Finding> findIn(DiffChunk chunk);
}
