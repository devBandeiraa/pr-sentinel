package dev.bandeira.prsentinel.publisher.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.publisher.application.InlineCommentsRejectedException;
import dev.bandeira.prsentinel.publisher.domain.GithubReviewEvent;
import dev.bandeira.prsentinel.publisher.domain.InlineComment;
import dev.bandeira.prsentinel.publisher.domain.RenderedReview;
import dev.bandeira.prsentinel.publisher.domain.ReviewMarker;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * O adaptador da API de review do GitHub.
 *
 * <p>Usa {@link MockRestServiceServer} em vez de WireMock, como no diff-fetcher: o cliente é um
 * {@link RestClient} e o spring-test já intercepta a chamada.
 */
class GithubPullRequestReviewsTest {

  private static final String BASE_URL = "https://api.github.com";
  private static final String REVIEWS_URL =
      BASE_URL + "/repos/devBandeiraa/pr-sentinel/pulls/7/reviews";
  private static final PullRequestRef PULL_REQUEST =
      new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123");
  private static final UUID REVIEW_ID = UUID.randomUUID();

  private final InstallationTokenProvider tokenProvider = mock(InstallationTokenProvider.class);
  private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final GithubPullRequestReviews reviews =
      new GithubPullRequestReviews(builder.build(), tokenProvider);

  @Test
  void postaAReviewComCommitBodyEventoEComentarios() {
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    server
        .expect(times(1), requestTo(REVIEWS_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer ghs-token"))
        .andExpect(header("Accept", "application/vnd.github+json"))
        .andExpect(jsonPath("$.commit_id").value("abc123"))
        .andExpect(jsonPath("$.event").value("COMMENT"))
        .andExpect(jsonPath("$.body").value(Matchers.containsString("PR Sentinel")))
        .andExpect(jsonPath("$.comments[0].path").value("src/main/java/Login.java"))
        .andExpect(jsonPath("$.comments[0].line").value(11))
        .andExpect(jsonPath("$.comments[0].side").value("RIGHT"))
        .andRespond(withSuccess());

    reviews.publish(PULL_REQUEST, reviewWithComment(), GithubReviewEvent.COMMENT);

    server.verify();
  }

  @Test
  void reviewSemComentarioNaoMandaOCampoComments() {
    // O GitHub espera o campo ausente, não uma lista vazia.
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    server
        .expect(times(1), requestTo(REVIEWS_URL))
        .andExpect(jsonPath("$.comments").doesNotExist())
        .andRespond(withSuccess());

    reviews.publish(
        PULL_REQUEST, new RenderedReview("## PR Sentinel", List.of()), GithubReviewEvent.COMMENT);

    server.verify();
  }

  @Test
  void requestChangesVaiComoEventoNoCorpo() {
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    server
        .expect(times(1), requestTo(REVIEWS_URL))
        .andExpect(jsonPath("$.event").value("REQUEST_CHANGES"))
        .andRespond(withSuccess());

    reviews.publish(PULL_REQUEST, reviewWithComment(), GithubReviewEvent.REQUEST_CHANGES);

    server.verify();
  }

  @Test
  void o422ComInlineViraRecusaDeComentarioInline() {
    // É o caso de linha fora do diff: a requisição inteira é recusada, e o caso de uso precisa
    // saber disso para cair na review sem inline (ADR-009).
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    server
        .expect(times(1), requestTo(REVIEWS_URL))
        .andRespond(
            withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .body("{\"message\":\"line must be part of the diff\"}")
                .contentType(MediaType.APPLICATION_JSON));

    assertThatThrownBy(
            () -> reviews.publish(PULL_REQUEST, reviewWithComment(), GithubReviewEvent.COMMENT))
        .isInstanceOf(InlineCommentsRejectedException.class);
  }

  @Test
  void o422SemInlineNaoViraRecusaDeInline() {
    // Sem comentário posicionado, 422 é outra coisa e não existe fallback que ajude.
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    server
        .expect(times(1), requestTo(REVIEWS_URL))
        .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

    assertThatThrownBy(
            () ->
                reviews.publish(
                    PULL_REQUEST,
                    new RenderedReview("## PR Sentinel", List.of()),
                    GithubReviewEvent.COMMENT))
        .isInstanceOf(HttpClientErrorException.class)
        .isNotInstanceOf(InlineCommentsRejectedException.class);
  }

  @Test
  void outroErroDoGitHubPropagaComoEstá() {
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    server.expect(times(1), requestTo(REVIEWS_URL)).andRespond(withResourceNotFound());

    assertThatThrownBy(
            () -> reviews.publish(PULL_REQUEST, reviewWithComment(), GithubReviewEvent.COMMENT))
        .isInstanceOf(HttpClientErrorException.class);
  }

  @Test
  void aMensagemDeErroNaoVazaOToken() {
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token-secreto");
    server.expect(times(1), requestTo(REVIEWS_URL)).andRespond(withResourceNotFound());

    assertThatThrownBy(
            () -> reviews.publish(PULL_REQUEST, reviewWithComment(), GithubReviewEvent.COMMENT))
        .satisfies(
            e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("ghs-token-secreto"));
  }

  @Test
  void reconheceAReviewJaPublicadaPeloMarcador() {
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    server
        .expect(times(1), requestTo(Matchers.startsWith(REVIEWS_URL)))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "[{\"body\":\"review de uma pessoa\"},{\"body\":\"resumo %s\"}]"
                    .formatted(ReviewMarker.of(REVIEW_ID)),
                MediaType.APPLICATION_JSON));

    assertThat(reviews.alreadyPublished(PULL_REQUEST, REVIEW_ID)).isTrue();
  }

  @Test
  void prSemAReviewDoSentinelNaoEstaPublicado() {
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    server
        .expect(times(1), requestTo(Matchers.startsWith(REVIEWS_URL)))
        .andRespond(
            withSuccess("[{\"body\":\"review de uma pessoa\"}]", MediaType.APPLICATION_JSON));

    assertThat(reviews.alreadyPublished(PULL_REQUEST, REVIEW_ID)).isFalse();
  }

  @Test
  void naoConfundeAReviewDeOutraAnaliseNoMesmoPr() {
    // Um PR atualizado tem várias reviews do Sentinel, uma por push.
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    server
        .expect(times(1), requestTo(Matchers.startsWith(REVIEWS_URL)))
        .andRespond(
            withSuccess(
                "[{\"body\":\"%s\"}]".formatted(ReviewMarker.of(UUID.randomUUID())),
                MediaType.APPLICATION_JSON));

    assertThat(reviews.alreadyPublished(PULL_REQUEST, REVIEW_ID)).isFalse();
  }

  @Test
  void segueAPaginacaoDasReviewsExistentes() {
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    String secondPage = REVIEWS_URL + "?per_page=100&page=2";

    HttpHeaders link = new HttpHeaders();
    link.add(HttpHeaders.LINK, "<" + secondPage + ">; rel=\"next\"");

    server
        .expect(times(1), requestTo(Matchers.startsWith(REVIEWS_URL)))
        .andRespond(
            withSuccess("[{\"body\":\"outra review\"}]", MediaType.APPLICATION_JSON).headers(link));
    server
        .expect(times(1), requestTo(secondPage))
        .andRespond(
            withSuccess(
                "[{\"body\":\"%s\"}]".formatted(ReviewMarker.of(REVIEW_ID)),
                MediaType.APPLICATION_JSON));

    assertThat(reviews.alreadyPublished(PULL_REQUEST, REVIEW_ID)).isTrue();
    server.verify();
  }

  private static RenderedReview reviewWithComment() {
    return new RenderedReview(
        "## PR Sentinel — mudanças solicitadas\n\n" + ReviewMarker.of(REVIEW_ID),
        List.of(new InlineComment("src/main/java/Login.java", 11, "**CRITICAL — SQL injection**")));
  }
}
