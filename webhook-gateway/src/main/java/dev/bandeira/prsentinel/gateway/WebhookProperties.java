package dev.bandeira.prsentinel.gateway;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "github.webhook")
public record WebhookProperties(@NotBlank String secret) {
}
