package dev.bandeira.prsentinel.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bandeira.prsentinel.agent.domain.FindingFilter;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent.Status;
import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent.DiffChunk;
import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.Severity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Orquestração do agente: analisar cada chunk, filtrar e publicar o resultado.
 *
 * <p>Usa o {@link FindingFilter} real porque ele já tem teste próprio e porque a ordem importa — o
 * filtro acontece depois do LLM e antes da publicação.
 */
class ReviewChunksUseCaseTest {

  private static final UUID REVIEW_ID = UUID.randomUUID();
  private static final PullRequestRef PULL_REQUEST =
      new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123");

  private final FakeFindingSource findingSource = new FakeFindingSource();
  private final FakeAgentResultPublisher publisher = new FakeAgentResultPublisher();

  private final ReviewChunksUseCase useCase =
      new ReviewChunksUseCase(findingSource, publisher, new FindingFilter(0.6), AgentType.SECURITY);

  @Test
  void analisaTodosOsChunksEPublicaOsAchadosJuntos() {
    findingSource.respondWith("src/A.java", finding("src/A.java", 0.9));
    findingSource.respondWith("src/B.java", finding("src/B.java", 0.8));

    useCase.handle(diffReady(chunk("src/A.java", 0), chunk("src/B.java", 0)));

    AgentCompletedEvent published = publisher.onlyPublished();
    assertThat(published.status()).isEqualTo(Status.SUCCESS);
    assertThat(published.findings())
        .extracting(Finding::file)
        .containsExactly("src/A.java", "src/B.java");
    assertThat(findingSource.analyzed()).hasSize(2);
  }

  @Test
  void preservaOReviewIdEOTipoDoAgente() {
    findingSource.respondWith("src/A.java", finding("src/A.java", 0.9));

    useCase.handle(diffReady(chunk("src/A.java", 0)));

    AgentCompletedEvent published = publisher.onlyPublished();
    assertThat(published.reviewId()).isEqualTo(REVIEW_ID);
    assertThat(published.agent()).isEqualTo(AgentType.SECURITY);
    assertThat(published.errorMessage()).isNull();
  }

  @Test
  void diffSemChunkPublicaSucessoSemChamarOLlm() {
    // O diff-fetcher publica lista vazia quando nada é revisável (ADR-007). Chamar o LLM aqui
    // seria pagar três requisições para analisar nada.
    useCase.handle(diffReady());

    AgentCompletedEvent published = publisher.onlyPublished();
    assertThat(published.status()).isEqualTo(Status.SUCCESS);
    assertThat(published.findings()).isEmpty();
    assertThat(findingSource.analyzed()).isEmpty();
  }

  @Test
  void achadoAbaixoDoLimiarNaoEhPublicado() {
    findingSource.respondWith("src/A.java", finding("src/A.java", 0.9), finding("src/A.java", 0.3));

    useCase.handle(diffReady(chunk("src/A.java", 0)));

    assertThat(publisher.onlyPublished().findings())
        .extracting(Finding::confidence)
        .containsExactly(0.9);
  }

  @Test
  void chunkSemAchadoNaoImpedeOsOutros() {
    findingSource.respondWith("src/A.java");
    findingSource.respondWith("src/B.java", finding("src/B.java", 0.9));

    useCase.handle(diffReady(chunk("src/A.java", 0), chunk("src/B.java", 0)));

    assertThat(publisher.onlyPublished().findings()).hasSize(1);
  }

  @Test
  void falhaDoLlmPublicaFailedEmVezDeDeixarAReviewPendurada() {
    // Publicar FAILED é o que permite ao orchestrator fechar uma review parcial (ADR-003).
    // Morrer calado deixaria a review esperando até o timeout.
    findingSource.failWith(new IllegalStateException("provedor fora do ar"));

    useCase.handle(diffReady(chunk("src/A.java", 0)));

    AgentCompletedEvent published = publisher.onlyPublished();
    assertThat(published.status()).isEqualTo(Status.FAILED);
    assertThat(published.findings()).isEmpty();
    assertThat(published.errorMessage()).isNotBlank();
  }

  @Test
  void falhaNoMeioDescartaOsAchadosParciais() {
    // SUCCESS com parte dos chunks analisados seria pior que FAILED: o orchestrator leria como
    // review completa e publicaria no PR uma análise que cobriu metade do diff, sem avisar.
    findingSource.respondWith("src/A.java", finding("src/A.java", 0.9));
    findingSource.failOn("src/B.java", new IllegalStateException("estourou o contexto"));

    useCase.handle(diffReady(chunk("src/A.java", 0), chunk("src/B.java", 0)));

    AgentCompletedEvent published = publisher.onlyPublished();
    assertThat(published.status()).isEqualTo(Status.FAILED);
    assertThat(published.findings()).isEmpty();
  }

  @Test
  void mensagemDeErroNaoCarregaOConteudoDoDiff() {
    // errorMessage é gravado no banco pelo orchestrator e pode chegar ao PR. A exceção do cliente
    // de LLM costuma trazer o corpo da requisição, que é o código do PR.
    findingSource.failWith(
        new IllegalStateException("falhou ao enviar: @@ -1 +1 @@ +senha = \"nao-pode-vazar\""));

    useCase.handle(diffReady(chunk("src/A.java", 0)));

    assertThat(publisher.onlyPublished().errorMessage())
        .doesNotContain("nao-pode-vazar")
        .doesNotContain("@@");
  }

  @Test
  void publicaExatamenteUmEventoPorDiff() {
    findingSource.respondWith("src/A.java", finding("src/A.java", 0.9));

    useCase.handle(diffReady(chunk("src/A.java", 0), chunk("src/A.java", 1)));

    assertThat(publisher.published()).hasSize(1);
  }

  private static DiffReadyEvent diffReady(DiffChunk... chunks) {
    return new DiffReadyEvent(REVIEW_ID, PULL_REQUEST, List.of(chunks));
  }

  private static DiffChunk chunk(String file, int index) {
    return new DiffChunk(file, "java", index, "@@ -1,2 +1,3 @@\n ctx\n+novo");
  }

  private static Finding finding(String file, double confidence) {
    return new Finding(
        file,
        42,
        Severity.HIGH,
        "SQL concatenado",
        "A query monta o WHERE com concatenação.",
        "Use PreparedStatement.",
        confidence);
  }
}
