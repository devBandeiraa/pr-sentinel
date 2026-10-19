package dev.bandeira.prsentinel.publisher.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Como a review aparece no PR.
 *
 * @param requestChanges quando ligado, veredito {@code CHANGES_REQUESTED} posta {@code
 *     REQUEST_CHANGES} e bloqueia o merge em repositório com branch protection. Vem desligado de
 *     propósito: achado de LLM passou por um limiar de confiança, não por uma pessoa, e travar o
 *     merge do time com base nisso é o caminho mais curto para alguém desligar o bot (ADR-009).
 */
@ConfigurationProperties(prefix = "publisher")
public record PublisherProperties(boolean requestChanges) {}
