package dev.bandeira.prsentinel.publisher;

import dev.bandeira.prsentinel.common.messaging.QueueDeclarations;
import dev.bandeira.prsentinel.common.messaging.Topology;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GithubPublisherApplication {

    static final String SERVICE = "github-publisher";

    public static void main(String[] args) {
        SpringApplication.run(GithubPublisherApplication.class, args);
    }

    @Bean
    Declarables publisherQueue() {
        return QueueDeclarations.consumerQueue(SERVICE, Topology.RK_REVIEW_READY);
    }
}
