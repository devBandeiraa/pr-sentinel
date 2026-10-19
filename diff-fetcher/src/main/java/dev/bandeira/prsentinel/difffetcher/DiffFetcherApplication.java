package dev.bandeira.prsentinel.difffetcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DiffFetcherApplication {

  public static void main(String[] args) {
    SpringApplication.run(DiffFetcherApplication.class, args);
  }
}
