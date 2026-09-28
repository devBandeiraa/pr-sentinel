package dev.bandeira.prsentinel.orchestrator;

import dev.bandeira.prsentinel.common.messaging.QueueDeclarations;
import dev.bandeira.prsentinel.common.messaging.Topology;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OrchestratorApplication {

    static final String REVIEWS_SERVICE = "orchestrator-reviews";
    static final String RESULTS_SERVICE = "orchestrator-results";

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }

    /** Escuta o início da review para registrar o estado PENDING. */
    @Bean
    Declarables reviewRequestedQueue() {
        return QueueDeclarations.consumerQueue(REVIEWS_SERVICE, Topology.RK_REVIEW_REQUESTED);
    }

    /** Escuta os resultados de cada agente. */
    @Bean
    Declarables agentCompletedQueue() {
        return QueueDeclarations.consumerQueue(RESULTS_SERVICE, Topology.RK_AGENT_COMPLETED);
    }
}
