package dev.bandeira.prsentinel.difffetcher;

import dev.bandeira.prsentinel.common.messaging.QueueDeclarations;
import dev.bandeira.prsentinel.common.messaging.Topology;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class DiffFetcherApplication {

  static final String SERVICE = "diff-fetcher";

  public static void main(String[] args) {
    SpringApplication.run(DiffFetcherApplication.class, args);
  }

  @Bean
  Declarables diffFetcherQueue() {
    return QueueDeclarations.consumerQueue(SERVICE, Topology.RK_REVIEW_REQUESTED);
  }
}
