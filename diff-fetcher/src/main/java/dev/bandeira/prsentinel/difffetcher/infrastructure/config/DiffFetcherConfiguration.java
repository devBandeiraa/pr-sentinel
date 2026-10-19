package dev.bandeira.prsentinel.difffetcher.infrastructure.config;

import dev.bandeira.prsentinel.common.messaging.QueueDeclarations;
import dev.bandeira.prsentinel.common.messaging.Topology;
import dev.bandeira.prsentinel.difffetcher.application.FetchDiffUseCase;
import dev.bandeira.prsentinel.difffetcher.application.port.DiffPublisher;
import dev.bandeira.prsentinel.difffetcher.application.port.PullRequestFiles;
import dev.bandeira.prsentinel.difffetcher.domain.DiffChunker;
import dev.bandeira.prsentinel.difffetcher.domain.DiffFilter;
import dev.bandeira.prsentinel.difffetcher.infrastructure.github.AppJwtFactory;
import dev.bandeira.prsentinel.difffetcher.infrastructure.github.GithubPullRequestFiles;
import dev.bandeira.prsentinel.difffetcher.infrastructure.github.InstallationTokenProvider;
import dev.bandeira.prsentinel.difffetcher.infrastructure.github.PrivateKeyLoader;
import dev.bandeira.prsentinel.difffetcher.infrastructure.messaging.ReviewRequestedListener;
import java.time.Clock;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Monta o serviço: fila, cliente HTTP, autenticação e caso de uso.
 *
 * <p>As classes de {@code domain} e {@code application} não são anotadas com {@code @Component} de
 * propósito — quem decide como elas são construídas é a infraestrutura, e assim elas continuam
 * instanciáveis com um {@code new} num teste de unidade.
 */
@Configuration
public class DiffFetcherConfiguration {

  /** Fila do serviço ligada a {@code pr.review.requested}, com DLQ (ADR-001). */
  @Bean
  Declarables diffFetcherQueue() {
    return QueueDeclarations.consumerQueue(
        ReviewRequestedListener.SERVICE, Topology.RK_REVIEW_REQUESTED);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  RestClient githubRestClient(RestClient.Builder builder, GithubAppProperties properties) {
    return builder.baseUrl(properties.apiBaseUrl()).build();
  }

  /**
   * A chave é lida uma vez, no boot. Arquivo ausente ou em PKCS#1 derruba a subida do serviço com
   * mensagem dizendo o que fazer — melhor que descobrir no primeiro PR do dia (ADR-006).
   */
  @Bean
  AppJwtFactory appJwtFactory(GithubAppProperties properties, Clock clock) {
    return new AppJwtFactory(
        properties.appId(), PrivateKeyLoader.load(properties.privateKeyPath()), clock);
  }

  @Bean
  InstallationTokenProvider installationTokenProvider(
      RestClient githubRestClient, AppJwtFactory appJwtFactory, Clock clock) {
    return new InstallationTokenProvider(githubRestClient, appJwtFactory, clock);
  }

  @Bean
  PullRequestFiles pullRequestFiles(
      RestClient githubRestClient, InstallationTokenProvider installationTokenProvider) {
    return new GithubPullRequestFiles(githubRestClient, installationTokenProvider);
  }

  @Bean
  DiffFilter diffFilter(DiffProperties properties) {
    return new DiffFilter(properties.ignoredPatterns());
  }

  @Bean
  DiffChunker diffChunker(DiffProperties properties) {
    return new DiffChunker(properties.maxChunkChars());
  }

  @Bean
  FetchDiffUseCase fetchDiffUseCase(
      PullRequestFiles pullRequestFiles,
      DiffFilter diffFilter,
      DiffChunker diffChunker,
      DiffPublisher diffPublisher) {
    return new FetchDiffUseCase(pullRequestFiles, diffFilter, diffChunker, diffPublisher);
  }
}
