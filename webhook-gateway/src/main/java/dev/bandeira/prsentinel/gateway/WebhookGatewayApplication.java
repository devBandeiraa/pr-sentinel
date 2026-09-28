package dev.bandeira.prsentinel.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WebhookGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebhookGatewayApplication.class, args);
    }
}
