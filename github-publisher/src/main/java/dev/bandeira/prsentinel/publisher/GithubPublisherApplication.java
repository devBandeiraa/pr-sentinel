package dev.bandeira.prsentinel.publisher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GithubPublisherApplication {

  public static void main(String[] args) {
    SpringApplication.run(GithubPublisherApplication.class, args);
  }
}
