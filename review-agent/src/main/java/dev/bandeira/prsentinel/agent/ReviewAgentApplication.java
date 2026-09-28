package dev.bandeira.prsentinel.agent;

import dev.bandeira.prsentinel.common.messaging.QueueDeclarations;
import dev.bandeira.prsentinel.common.messaging.Topology;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReviewAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewAgentApplication.class, args);
    }

    /** Cada tipo de agente tem sua própria fila, todas ligadas a pr.diff.ready (fan-out). */
    @Bean
    Declarables agentQueue(AgentProperties properties) {
        return QueueDeclarations.consumerQueue(properties.serviceName(), Topology.RK_DIFF_READY);
    }

    /** Nome da fila exposto como bean para o @RabbitListener resolver via SpEL. */
    @Bean
    String agentQueueName(AgentProperties properties) {
        return Topology.queue(properties.serviceName());
    }
}
