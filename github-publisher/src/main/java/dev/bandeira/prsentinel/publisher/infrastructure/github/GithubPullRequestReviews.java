package dev.bandeira.prsentinel.publisher.infrastructure.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.publisher.application.InlineCommentsRejectedException;
import dev.bandeira.prsentinel.publisher.application.port.PullRequestReviews;
import dev.bandeira.prsentinel.publisher.domain.GithubReviewEvent;
import dev.bandeira.prsentinel.publisher.domain.InlineComment;
import dev.bandeira.prsentinel.publisher.domain.RenderedReview;
import dev.bandeira.prsentinel.publisher.domain.ReviewMarker;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Posta a review no Pull Request (ADR-009).
 *
 * <p>Duas particularidades da API do GitHub moldam esta classe. A primeira: comentário inline só é
 * aceito em linha que faça parte do diff, e basta uma estar fora para a requisição inteira ser
 * recusada com {@code 422} — traduzido aqui em {@link InlineCommentsRejectedException} para o caso
 * de uso cair na review sem inline. A segunda: não existe chave de idempotência na API, então a
 * checagem é feita procurando o marcador do PR Sentinel entre as reviews que já estão lá.
 */
public class GithubPullRequestReviews implements PullRequestReviews {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final int PER_PAGE = 100;

  /**
   * Teto de páginas ao varrer as reviews existentes. PR com mais de mil reviews não existe na
   * prática; o limite está aqui para um {@code Link} malformado não virar laço infinito dentro do
   * consumidor.
   */
  static final int MAX_PAGES = 10;

  private static final Pattern NEXT_LINK =
      Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"", Pattern.CASE_INSENSITIVE);

  private static final ParameterizedTypeReference<List<ExistingReview>> REVIEW_LIST =
      new ParameterizedTypeReference<>() {};

  private final RestClient restClient;
  private final InstallationTokenProvider tokenProvider;

  public GithubPullRequestReviews(RestClient restClient, InstallationTokenProvider tokenProvider) {
    this.restClient = restClient;
    this.tokenProvider = tokenProvider;
  }

  @Override
  public boolean alreadyPublished(PullRequestRef pullRequest, UUID reviewId) {
    String token = tokenProvider.tokenFor(pullRequest.installationId());
    URI nextPage = null;

    for (int page = 0; page < MAX_PAGES; page++) {
      ResponseEntity<List<ExistingReview>> response = listReviews(pullRequest, nextPage, token);

      List<ExistingReview> body = response.getBody();
      if (body != null
          && body.stream().anyMatch(review -> ReviewMarker.isPresentIn(review.body(), reviewId))) {
        return true;
      }

      nextPage = nextPageOf(response.getHeaders());
      if (nextPage == null) {
        return false;
      }
    }
    return false;
  }

  @Override
  public void publish(PullRequestRef pullRequest, RenderedReview review, GithubReviewEvent event) {
    String token = tokenProvider.tokenFor(pullRequest.installationId());

    ReviewRequest request =
        new ReviewRequest(
            pullRequest.headSha(),
            review.summary(),
            event.name(),
            review.comments().stream().map(ReviewComment::from).toList());

    try {
      restClient
          .post()
          .uri(
              uriBuilder ->
                  uriBuilder
                      .path("/repos/{owner}/{repo}/pulls/{number}/reviews")
                      .build(pullRequest.owner(), pullRequest.repo(), pullRequest.number()))
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .header(HttpHeaders.ACCEPT, ACCEPT)
          .body(request)
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException e) {
      // 422 com comentário inline é quase sempre linha fora do diff. Sem comentário inline, 422 é
      // outra coisa (corpo vazio, commit que não é o head) e não tem fallback que ajude.
      if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY && review.hasInlineComments()) {
        throw new InlineCommentsRejectedException();
      }
      throw e;
    }
  }

  private ResponseEntity<List<ExistingReview>> listReviews(
      PullRequestRef pullRequest, URI nextPage, String token) {
    RestClient.RequestHeadersSpec<?> spec =
        nextPage != null
            ? restClient.get().uri(nextPage)
            : restClient
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/repos/{owner}/{repo}/pulls/{number}/reviews")
                            .queryParam("per_page", PER_PAGE)
                            .build(pullRequest.owner(), pullRequest.repo(), pullRequest.number()));

    return spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header(HttpHeaders.ACCEPT, ACCEPT)
        .retrieve()
        .toEntity(REVIEW_LIST);
  }

  private static URI nextPageOf(HttpHeaders headers) {
    List<String> links = headers.get(HttpHeaders.LINK);
    if (links == null) {
      return null;
    }
    for (String link : links) {
      Matcher matcher = NEXT_LINK.matcher(link);
      if (matcher.find()) {
        return URI.create(matcher.group(1));
      }
    }
    return null;
  }

  /**
   * O corpo do {@code POST /pulls/{n}/reviews}.
   *
   * <p>{@code commitId} vai como {@code commit_id} por anotação, e não renomeando o campo: nome de
   * código é em inglês e em camelCase (CLAUDE.md), e o snake_case é detalhe do JSON do GitHub.
   *
   * <p>{@code NON_EMPTY} faz a lista vazia de comentários desaparecer do JSON, que é o formato que
   * o GitHub espera para review só com resumo.
   */
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  private record ReviewRequest(
      @JsonProperty("commit_id") String commitId,
      String body,
      String event,
      List<ReviewComment> comments) {}

  /**
   * Um comentário posicionado. {@code side: RIGHT} é o lado novo do diff — é onde o código
   * adicionado vive, e é sobre ele que os agentes opinam (ADR-007).
   */
  private record ReviewComment(String path, int line, String side, String body) {

    static ReviewComment from(InlineComment comment) {
      return new ReviewComment(comment.path(), comment.line(), "RIGHT", comment.body());
    }
  }

  /** Só o corpo interessa: é nele que o marcador do PR Sentinel está. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ExistingReview(String body) {}
}
