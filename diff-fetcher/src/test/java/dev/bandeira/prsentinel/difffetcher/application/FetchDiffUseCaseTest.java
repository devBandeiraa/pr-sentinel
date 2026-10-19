package dev.bandeira.prsentinel.difffetcher.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bandeira.prsentinel.common.event.DiffReadyEvent;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent.DiffChunk;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.difffetcher.domain.ChangedFile;
import dev.bandeira.prsentinel.difffetcher.domain.DiffChunker;
import dev.bandeira.prsentinel.difffetcher.domain.DiffFilter;
import dev.bandeira.prsentinel.difffetcher.domain.FileStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Orquestração do caso de uso: buscar, filtrar, fatiar, publicar.
 *
 * <p>Usa o {@link DiffFilter} e o {@link DiffChunker} reais, só com fakes nas portas. Eles são
 * puros e já têm teste próprio; dublá-los esconderia justamente o que interessa aqui, que é o
 * filtro acontecer antes do fatiamento.
 */
class FetchDiffUseCaseTest {

  private static final UUID REVIEW_ID = UUID.randomUUID();
  private static final PullRequestRef PULL_REQUEST =
      new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123");
  private static final String PATCH = "@@ -1,2 +1,3 @@\n contexto\n+adicionada";

  private final FakePullRequestFiles files = new FakePullRequestFiles();
  private final FakeDiffPublisher publisher = new FakeDiffPublisher();

  private final FetchDiffUseCase useCase =
      new FetchDiffUseCase(
          files,
          new DiffFilter(List.of("**/package-lock.json", "**/*.min.js")),
          new DiffChunker(12_000),
          publisher);

  @Test
  void publicaChunkApenasDosArquivosRevisaveis() {
    files.returning(
        new ChangedFile("src/main/java/App.java", FileStatus.MODIFIED, PATCH),
        new ChangedFile("package-lock.json", FileStatus.MODIFIED, PATCH),
        new ChangedFile("static/app.min.js", FileStatus.MODIFIED, PATCH),
        new ChangedFile("docs/logo.png", FileStatus.ADDED, null));

    useCase.handle(reviewRequested());

    assertThat(publisher.published()).hasSize(1);
    assertThat(publisher.published().get(0).chunks())
        .extracting(DiffChunk::file)
        .containsExactly("src/main/java/App.java");
  }

  @Test
  void preservaReviewIdEPullRequestNoEventoPublicado() {
    files.returning(new ChangedFile("src/App.java", FileStatus.MODIFIED, PATCH));

    useCase.handle(reviewRequested());

    DiffReadyEvent event = publisher.published().get(0);
    assertThat(event.reviewId()).isEqualTo(REVIEW_ID);
    assertThat(event.pullRequest()).isEqualTo(PULL_REQUEST);
  }

  @Test
  void prSemArquivoRevisavelPublicaEventoComChunksVazios() {
    // ADR-007: publicar vazio termina a review aprovando. Não publicar deixaria o orchestrator
    // esperando até o timeout por uma review que nunca teve o que analisar.
    files.returning(
        new ChangedFile("package-lock.json", FileStatus.MODIFIED, PATCH),
        new ChangedFile("src/Velho.java", FileStatus.REMOVED, PATCH));

    useCase.handle(reviewRequested());

    assertThat(publisher.published()).hasSize(1);
    assertThat(publisher.published().get(0).chunks()).isEmpty();
  }

  @Test
  void prSemNenhumArquivoPublicaEventoComChunksVazios() {
    files.returning();

    useCase.handle(reviewRequested());

    assertThat(publisher.published()).hasSize(1);
    assertThat(publisher.published().get(0).chunks()).isEmpty();
  }

  @Test
  void chunkIndexEhPorArquivoEPodeRepetirEntreArquivos() {
    files.returning(
        new ChangedFile("src/A.java", FileStatus.MODIFIED, PATCH),
        new ChangedFile("src/B.java", FileStatus.MODIFIED, PATCH));

    useCase.handle(reviewRequested());

    assertThat(publisher.published().get(0).chunks())
        .extracting(DiffChunk::file, DiffChunk::chunkIndex)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("src/A.java", 0),
            org.assertj.core.groups.Tuple.tuple("src/B.java", 0));
  }

  @Test
  void falhaAoBuscarOsArquivosPropagaENaoPublicaNada() {
    // Propagar é proposital: é a exceção que faz o listener rejeitar a mensagem, para o retry da
    // fila ou a DLQ decidirem o destino. Publicar um evento vazio aqui mentiria que o PR não tem
    // código para revisar.
    files.failWith(new IllegalStateException("API do GitHub fora"));

    assertThatThrownBy(() -> useCase.handle(reviewRequested()))
        .isInstanceOf(IllegalStateException.class);

    assertThat(publisher.published()).isEmpty();
  }

  private static ReviewRequestedEvent reviewRequested() {
    return new ReviewRequestedEvent(
        REVIEW_ID, "delivery-1", PULL_REQUEST, Instant.parse("2026-10-19T12:00:00Z"));
  }
}
