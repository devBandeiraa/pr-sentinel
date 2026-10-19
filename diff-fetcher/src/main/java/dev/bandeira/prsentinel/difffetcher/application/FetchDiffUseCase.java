package dev.bandeira.prsentinel.difffetcher.application;

import dev.bandeira.prsentinel.common.event.DiffReadyEvent;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent.DiffChunk;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.difffetcher.application.port.DiffPublisher;
import dev.bandeira.prsentinel.difffetcher.application.port.PullRequestFiles;
import dev.bandeira.prsentinel.difffetcher.domain.ChangedFile;
import dev.bandeira.prsentinel.difffetcher.domain.DiffChunker;
import dev.bandeira.prsentinel.difffetcher.domain.DiffFilter;
import java.util.ArrayList;
import java.util.List;

/**
 * Transforma "existe um PR para revisar" em "aqui está o código a revisar".
 *
 * <p>Ordem: buscar, filtrar, fatiar, publicar. Filtrar antes de fatiar não é detalhe — é o que
 * impede que lockfile e arquivo gerado virem chunk e custem três chamadas de LLM cada (ADR-007).
 */
public class FetchDiffUseCase {

  private final PullRequestFiles pullRequestFiles;
  private final DiffFilter filter;
  private final DiffChunker chunker;
  private final DiffPublisher publisher;

  public FetchDiffUseCase(
      PullRequestFiles pullRequestFiles,
      DiffFilter filter,
      DiffChunker chunker,
      DiffPublisher publisher) {
    this.pullRequestFiles = pullRequestFiles;
    this.filter = filter;
    this.chunker = chunker;
    this.publisher = publisher;
  }

  /**
   * @return o resumo do que aconteceu, para o adaptador registrar em log — é ele que conhece
   *     framework, e os números só existem aqui dentro
   */
  public DiffOutcome handle(ReviewRequestedEvent event) {
    List<ChangedFile> files = pullRequestFiles.fetch(event.pullRequest());

    List<ChangedFile> reviewable = files.stream().filter(filter::isReviewable).toList();

    List<DiffChunk> chunks = new ArrayList<>();
    for (ChangedFile file : reviewable) {
      chunks.addAll(chunker.chunk(file));
    }

    // Publica mesmo sem chunk nenhum: é o que faz a review terminar aprovada em vez de ficar
    // pendurada até o timeout do orchestrator (ADR-003 e ADR-007).
    publisher.publish(new DiffReadyEvent(event.reviewId(), event.pullRequest(), chunks));

    return new DiffOutcome(files.size(), reviewable.size(), chunks.size());
  }
}
