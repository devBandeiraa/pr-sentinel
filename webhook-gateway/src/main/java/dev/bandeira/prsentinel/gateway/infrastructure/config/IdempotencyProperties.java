package dev.bandeira.prsentinel.gateway.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retenção dos registros de idempotência.
 *
 * @param retention por quanto tempo um delivery id continua sendo considerado já processado. Depois
 *     disso uma reentrega manual pelo painel do GitHub volta a gerar review, que é o comportamento
 *     desejado (ADR-005).
 * @param purgeInterval de quanto em quanto tempo o expurgo roda.
 */
@ConfigurationProperties(prefix = "gateway.idempotency")
public record IdempotencyProperties(Duration retention, Duration purgeInterval) {

  public IdempotencyProperties {
    retention = retention != null ? retention : Duration.ofDays(30);
    purgeInterval = purgeInterval != null ? purgeInterval : Duration.ofHours(24);
  }
}
