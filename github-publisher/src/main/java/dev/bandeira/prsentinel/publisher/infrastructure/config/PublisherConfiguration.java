package dev.bandeira.prsentinel.publisher.infrastructure.config;

import dev.bandeira.prsentinel.common.messaging.QueueDeclarations;
import dev.bandeira.prsentinel.common.messaging.Topology;
import dev.bandeira.prsentinel.publisher.application.PublishReviewUseCase;
import dev.bandeira.prsentinel.publisher.application.port.PullRequestReviews;
import dev.bandeira.prsentinel.publisher.domain.ReviewEventPolicy;
import dev.bandeira.prsentinel.publisher.domain.ReviewRenderer;
import dev.bandeira.prsentinel.publisher.infrastructure.github.AppJwtFactory;
import dev.bandeira.prsentinel.publisher.infrastructure.github.GithubPullRequestReviews;
import dev.bandeira.prsentinel.publisher.infrastructure.github.InstallationTokenProvider;
import dev.bandeira.prsentinel.publisher.infrastructure.github.PrivateKeyLoader;
import dev.bandeira.prsentinel.publisher.infrastructure.messaging.ReviewReadyListener;
import java.time.Clock;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Monta o serviço: fila, cliente HTTP, autenticação e caso de uso. */
@Configuration
public class PublisherConfiguration {

  @Bean
  Declarables publisherQueue() {
    return QueueDeclarations.consumerQueue(ReviewReadyListener.SERVICE, Topology.RK_REVIEW_READY);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  RestClient githubRestClient(RestClient.Builder builder, GithubAppProperties properties) {
    return builder.baseUrl(properties.apiBaseUrl()).build();
  }

  /** A chave é lida uma vez, no boot: formato errado derruba a subida com instrução (ADR-006). */
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
  PullRequestReviews pullRequestReviews(
      RestClient githubRestClient, InstallationTokenProvider installationTokenProvider) {
    return new GithubPullRequestReviews(githubRestClient, installationTokenProvider);
  }

  @Bean
  ReviewRenderer reviewRenderer() {
    return new ReviewRenderer();
  }

  @Bean
  ReviewEventPolicy reviewEventPolicy(PublisherProperties properties) {
    return new ReviewEventPolicy(properties.requestChanges());
  }

  @Bean
  PublishReviewUseCase publishReviewUseCase(
      PullRequestReviews reviews, ReviewRenderer renderer, ReviewEventPolicy eventPolicy) {
    return new PublishReviewUseCase(reviews, renderer, eventPolicy);
  }
}
