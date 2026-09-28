package dev.bandeira.prsentinel.common.event;

import java.util.List;
import java.util.UUID;

/** Publicado pelo diff-fetcher com o diff já filtrado e dividido em chunks. */
public record DiffReadyEvent(UUID reviewId, PullRequestRef pullRequest, List<DiffChunk> chunks) {

  /** Trecho de diff de um arquivo, pequeno o bastante para caber no contexto do LLM. */
  public record DiffChunk(String file, String language, int chunkIndex, String patch) {}
}
