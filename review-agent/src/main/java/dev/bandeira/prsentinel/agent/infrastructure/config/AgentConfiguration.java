package dev.bandeira.prsentinel.agent.infrastructure.config;

import dev.bandeira.prsentinel.agent.application.ReviewChunksUseCase;
import dev.bandeira.prsentinel.agent.application.port.AgentResultPublisher;
import dev.bandeira.prsentinel.agent.application.port.FindingSource;
import dev.bandeira.prsentinel.agent.domain.FindingFilter;
import dev.bandeira.prsentinel.agent.infrastructure.llm.SpringAiFindingSource;
import dev.bandeira.prsentinel.common.messaging.QueueDeclarations;
import dev.bandeira.prsentinel.common.messaging.Topology;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/**
 * Monta o agente a partir do {@code AGENT_TYPE}: a fila, o prompt e o caso de uso.
 *
 * <p>É aqui que o mesmo código vira três serviços diferentes (ADR-002). Nada abaixo depende de qual
 * tipo é — quem decide é a configuração.
 */
@Configuration
public class AgentConfiguration {

  /** Fila deste tipo de agente, ligada a {@code pr.diff.ready}, com DLQ (ADR-001). */
  @Bean
  Declarables agentQueue(AgentProperties properties) {
    return QueueDeclarations.consumerQueue(properties.serviceName(), Topology.RK_DIFF_READY);
  }

  /**
   * Nome da fila exposto como bean porque o {@code @RabbitListener} resolve por SpEL: o nome só
   * existe em tempo de execução, depois de ler {@code AGENT_TYPE}.
   */
  @Bean
  String agentQueueName(AgentProperties properties) {
    return Topology.queue(properties.serviceName());
  }

  @Bean
  FindingFilter findingFilter(AgentProperties properties) {
    return new FindingFilter(properties.minConfidence());
  }

  @Bean
  FindingSource findingSource(
      ChatClient.Builder chatClientBuilder,
      AgentProperties properties,
      ResourceLoader resourceLoader) {
    return new SpringAiFindingSource(chatClientBuilder, properties.type(), resourceLoader);
  }

  @Bean
  ReviewChunksUseCase reviewChunksUseCase(
      FindingSource findingSource,
      AgentResultPublisher publisher,
      FindingFilter findingFilter,
      AgentProperties properties) {
    return new ReviewChunksUseCase(findingSource, publisher, findingFilter, properties.type());
  }
}
