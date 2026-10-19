package dev.bandeira.prsentinel.difffetcher.infrastructure.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.difffetcher.application.port.PullRequestFiles;
import dev.bandeira.prsentinel.difffetcher.domain.ChangedFile;
import dev.bandeira.prsentinel.difffetcher.domain.FileStatus;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Busca os arquivos do PR em {@code GET /repos/{owner}/{repo}/pulls/{number}/files} (ADR-007).
 *
 * <p>A paginação segue o header {@code Link}, que é a forma recomendada pelo GitHub. Contar itens
 * para adivinhar se existe outra página erra justamente quando o total é múltiplo exato de {@code
 * per_page}.
 */
public class GithubPullRequestFiles implements PullRequestFiles {

  private static final int PER_PAGE = 100;

  /**
   * Teto de páginas seguidas. São 1.000 arquivos, muito além de qualquer PR revisável — o limite
   * existe para que um {@code Link} malformado não vire laço infinito dentro do consumidor, com a
   * mensagem nunca terminando nem indo para a DLQ.
   */
  static final int MAX_PAGES = 10;

  private static final Pattern NEXT_LINK =
      Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"", Pattern.CASE_INSENSITIVE);

  private static final ParameterizedTypeReference<List<GithubFile>> FILE_LIST =
      new ParameterizedTypeReference<>() {};

  private static final String ACCEPT = "application/vnd.github+json";

  private final RestClient restClient;
  private final InstallationTokenProvider tokenProvider;

  public GithubPullRequestFiles(RestClient restClient, InstallationTokenProvider tokenProvider) {
    this.restClient = restClient;
    this.tokenProvider = tokenProvider;
  }

  @Override
  public List<ChangedFile> fetch(PullRequestRef pullRequest) {
    String token = tokenProvider.tokenFor(pullRequest.installationId());

    List<ChangedFile> files = new ArrayList<>();
    URI nextPage = null;

    for (int page = 0; page < MAX_PAGES; page++) {
      ResponseEntity<List<GithubFile>> response = request(pullRequest, nextPage, token);

      List<GithubFile> body = response.getBody();
      if (body != null) {
        body.stream().map(GithubPullRequestFiles::toChangedFile).forEach(files::add);
      }

      nextPage = nextPageOf(response.getHeaders());
      if (nextPage == null) {
        break;
      }
    }

    return List.copyOf(files);
  }

  /**
   * A primeira página é montada a partir do PR; as seguintes vêm como URL absoluta no {@code Link},
   * já codificada pelo GitHub, e por isso entram sem passar por expansão de template.
   */
  private ResponseEntity<List<GithubFile>> request(
      PullRequestRef pullRequest, URI nextPage, String token) {
    RestClient.RequestHeadersSpec<?> spec =
        nextPage != null
            ? restClient.get().uri(nextPage)
            : restClient
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/repos/{owner}/{repo}/pulls/{number}/files")
                            .queryParam("per_page", PER_PAGE)
                            .build(pullRequest.owner(), pullRequest.repo(), pullRequest.number()));

    return spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header(HttpHeaders.ACCEPT, ACCEPT)
        .retrieve()
        .toEntity(FILE_LIST);
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

  private static ChangedFile toChangedFile(GithubFile file) {
    return new ChangedFile(file.filename(), FileStatus.from(file.status()), file.patch());
  }

  /**
   * Só os três campos que o domínio usa.
   *
   * <p>A resposta do GitHub tem mais de dez, e {@code ignoreUnknown} é explícito em vez de confiar
   * na configuração do {@code ObjectMapper}: campo novo na API não pode derrubar a busca do diff.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record GithubFile(String filename, String status, String patch) {}
}
