package dev.bandeira.prsentinel.difffetcher.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.difffetcher.domain.ChangedFile;
import dev.bandeira.prsentinel.difffetcher.domain.FileStatus;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Adaptador da API do GitHub: busca os arquivos do PR, paginando.
 *
 * <p>A paginação segue o header {@code Link}, que é a forma recomendada pelo GitHub — contar itens
 * para adivinhar se há outra página erra no caso em que o total é múltiplo exato de {@code
 * per_page}.
 */
class GithubPullRequestFilesTest {

  private static final String BASE_URL = "https://api.github.com";
  private static final String FIRST_PAGE =
      BASE_URL + "/repos/devBandeiraa/pr-sentinel/pulls/7/files";
  private static final PullRequestRef PULL_REQUEST =
      new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123");

  private final InstallationTokenProvider tokenProvider = mock(InstallationTokenProvider.class);
  private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final GithubPullRequestFiles files =
      new GithubPullRequestFiles(builder.build(), tokenProvider);

  @Test
  void mapeiaOsCamposDoArquivo() {
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    server
        .expect(times(1), requestToUriTemplate(FIRST_PAGE + "?per_page=100"))
        .andRespond(
            withSuccess(
                """
                [
                  {
                    "filename": "src/main/java/App.java",
                    "status": "modified",
                    "patch": "@@ -1,2 +1,3 @@\\n contexto\\n+adicionada"
                  }
                ]
                """,
                MediaType.APPLICATION_JSON));

    List<ChangedFile> result = files.fetch(PULL_REQUEST);

    assertThat(result).hasSize(1);
    ChangedFile file = result.get(0);
    assertThat(file.path()).isEqualTo("src/main/java/App.java");
    assertThat(file.status()).isEqualTo(FileStatus.MODIFIED);
    assertThat(file.patch()).isEqualTo("@@ -1,2 +1,3 @@\n contexto\n+adicionada");
  }

  @Test
  void pedeCemPorPaginaEAutenticaComOTokenDaInstalacao() {
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    server
        .expect(times(1), requestTo(Matchers.startsWith(FIRST_PAGE)))
        .andExpect(queryParam("per_page", "100"))
        .andExpect(header("Authorization", "Bearer ghs-token"))
        .andExpect(header("Accept", "application/vnd.github+json"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    files.fetch(PULL_REQUEST);

    server.verify();
  }

  @Test
  void segueOLinkDaProximaPaginaEJuntaOsArquivos() {
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    String secondPage = FIRST_PAGE + "?per_page=100&page=2";

    server
        .expect(times(1), requestTo(Matchers.startsWith(FIRST_PAGE)))
        .andRespond(
            withSuccess(onlyFile("src/A.java"), MediaType.APPLICATION_JSON)
                .headers(linkTo(secondPage)));
    server
        .expect(times(1), requestTo(secondPage))
        .andRespond(withSuccess(onlyFile("src/B.java"), MediaType.APPLICATION_JSON));

    List<ChangedFile> result = files.fetch(PULL_REQUEST);

    assertThat(result).extracting(ChangedFile::path).containsExactly("src/A.java", "src/B.java");
    server.verify();
  }

  @Test
  void paraNoLimiteDePaginasEmVezDeSeguirParaSempre() {
    // Link que sempre aponta para outra página não pode virar laço infinito dentro de um
    // consumidor de fila: a mensagem nunca terminaria nem iria para a DLQ.
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    RestClient.Builder loopingBuilder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer loopingServer =
        MockRestServiceServer.bindTo(loopingBuilder).ignoreExpectOrder(true).build();
    loopingServer
        .expect(manyTimes(), requestTo(Matchers.containsString("/files")))
        .andRespond(
            withSuccess(onlyFile("src/A.java"), MediaType.APPLICATION_JSON)
                .headers(linkTo(FIRST_PAGE + "?per_page=100&page=2")));

    List<ChangedFile> result =
        new GithubPullRequestFiles(loopingBuilder.build(), tokenProvider).fetch(PULL_REQUEST);

    assertThat(result).hasSize(GithubPullRequestFiles.MAX_PAGES);
  }

  @Test
  void arquivoSemPatchViraPatchNulo() {
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    server
        .expect(times(1), requestTo(Matchers.startsWith(FIRST_PAGE)))
        .andRespond(
            withSuccess(
                "[{\"filename\": \"docs/logo.png\", \"status\": \"added\"}]",
                MediaType.APPLICATION_JSON));

    List<ChangedFile> result = files.fetch(PULL_REQUEST);

    assertThat(result.get(0).patch()).isNull();
    assertThat(result.get(0).hasPatch()).isFalse();
  }

  @Test
  void statusDesconhecidoViraOther() {
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    server
        .expect(times(1), requestTo(Matchers.startsWith(FIRST_PAGE)))
        .andRespond(
            withSuccess(
                "[{\"filename\": \"a.java\", \"status\": \"inventado\", \"patch\": \"@@\"}]",
                MediaType.APPLICATION_JSON));

    assertThat(files.fetch(PULL_REQUEST).get(0).status()).isEqualTo(FileStatus.OTHER);
  }

  @Test
  void prInexistenteOuSemPermissaoPropagaOErro() {
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token");
    server
        .expect(times(1), requestTo(Matchers.startsWith(FIRST_PAGE)))
        .andRespond(withResourceNotFound());

    assertThatThrownBy(() -> files.fetch(PULL_REQUEST)).isInstanceOf(RuntimeException.class);
  }

  @Test
  void mensagemDeErroNaoVazaOToken() {
    when(tokenProvider.tokenFor(42L)).thenReturn("ghs-token-secreto");
    server
        .expect(times(1), requestTo(Matchers.startsWith(FIRST_PAGE)))
        .andRespond(withResourceNotFound());

    assertThatThrownBy(() -> files.fetch(PULL_REQUEST))
        .satisfies(
            e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("ghs-token-secreto"));
  }

  private static String onlyFile(String path) {
    return "[{\"filename\": \"%s\", \"status\": \"modified\", \"patch\": \"@@ -1 +1 @@\\n+ok\"}]"
        .formatted(path);
  }

  private static HttpHeaders linkTo(String nextPage) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.LINK, "<" + nextPage + ">; rel=\"next\"");
    return headers;
  }
}
