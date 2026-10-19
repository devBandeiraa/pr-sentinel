package dev.bandeira.prsentinel.orchestrator.infrastructure.config;

import dev.bandeira.prsentinel.common.messaging.QueueDeclarations;
import dev.bandeira.prsentinel.common.messaging.Topology;
import dev.bandeira.prsentinel.orchestrator.application.PublishTimedOutReviewsUseCase;
import dev.bandeira.prsentinel.orchestrator.application.RecordAgentResultUseCase;
import dev.bandeira.prsentinel.orchestrator.application.RegisterReviewUseCase;
import dev.bandeira.prsentinel.orchestrator.application.port.ReviewPublisher;
import dev.bandeira.prsentinel.orchestrator.application.port.Reviews;
import dev.bandeira.prsentinel.orchestrator.domain.FindingConsolidator;
import dev.bandeira.prsentinel.orchestrator.domain.VerdictPolicy;
import dev.bandeira.prsentinel.orchestrator.infrastructure.messaging.ReviewEventsListener;
import java.time.Clock;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Monta o orchestrator: as duas filas, as regras puras e os três casos de uso.
 *
 * <p>As classes de {@code domain} e {@code application} não são anotadas de propósito — quem decide
 * como elas são construídas é a infraestrutura, e assim continuam instanciáveis com um {@code new}
 * em teste de unidade.
 */
@Configuration
public class OrchestratorConfiguration {

  /** Escuta o início da review para registrar o estado PENDING. */
  @Bean
  Declarables reviewRequestedQueue() {
    return QueueDeclarations.consumerQueue(
        ReviewEventsListener.REVIEWS_SERVICE, Topology.RK_REVIEW_REQUESTED);
  }

  /** Escuta os resultados de cada agente, em fila própria. */
  @Bean
  Declarables agentCompletedQueue() {
    return QueueDeclarations.consumerQueue(
        ReviewEventsListener.RESULTS_SERVICE, Topology.RK_AGENT_COMPLETED);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  FindingConsolidator findingConsolidator() {
    return new FindingConsolidator();
  }

  @Bean
  VerdictPolicy verdictPolicy() {
    return new VerdictPolicy();
  }

  @Bean
  RegisterReviewUseCase registerReviewUseCase(Reviews reviews) {
    return new RegisterReviewUseCase(reviews);
  }

  @Bean
  RecordAgentResultUseCase recordAgentResultUseCase(
      Reviews reviews,
      ReviewPublisher publisher,
      FindingConsolidator consolidator,
      VerdictPolicy verdictPolicy,
      Clock clock) {
    return new RecordAgentResultUseCase(reviews, publisher, consolidator, verdictPolicy, clock);
  }

  @Bean
  PublishTimedOutReviewsUseCase publishTimedOutReviewsUseCase(
      Reviews reviews,
      ReviewPublisher publisher,
      FindingConsolidator consolidator,
      VerdictPolicy verdictPolicy,
      Clock clock,
      OrchestratorProperties properties) {
    return new PublishTimedOutReviewsUseCase(
        reviews, publisher, consolidator, verdictPolicy, clock, properties.agentTimeout());
  }
}
