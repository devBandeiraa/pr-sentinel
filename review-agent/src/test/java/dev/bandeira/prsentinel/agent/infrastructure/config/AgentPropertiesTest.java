package dev.bandeira.prsentinel.agent.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bandeira.prsentinel.common.event.AgentType;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * O mesmo módulo rodando como três containers (ADR-002).
 *
 * <p>O que muda entre eles é só {@code AGENT_TYPE}, e daí saem a fila e o prompt. Estes testes
 * prendem essa derivação para os três tipos de uma vez: pela skill {@code add-review-agent},
 * acrescentar um agente é somar um valor ao enum, um prompt e um container — e aqui um valor novo
 * sem prompt reprova o build em vez de derrubar o container no deploy.
 */
class AgentPropertiesTest {

  private final ResourceLoader resourceLoader = new DefaultResourceLoader();

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "SECURITY, review-agent-security",
    "PERFORMANCE, review-agent-performance",
    "STANDARDS, review-agent-standards"
  })
  void oNomeDoServicoSaiDoTipo(AgentType type, String expectedService) {
    assertThat(new AgentProperties(type, 0.6).serviceName()).isEqualTo(expectedService);
  }

  @ParameterizedTest
  @EnumSource(AgentType.class)
  void todoTipoDeAgenteTemPromptNoClasspath(AgentType type) {
    Resource prompt = promptOf(type);

    assertThat(prompt.exists())
        .as("falta prompts/%s.st — o container subiria sem prompt", type.name().toLowerCase())
        .isTrue();
  }

  @ParameterizedTest
  @EnumSource(AgentType.class)
  void todoPromptPedeFocoExclusivoEJsonVazioQuandoNadaForAchado(AgentType type) throws Exception {
    // As duas instruções sustentam decisões de arquitetura: o foco exclusivo mantém a separação
    // entre agentes da ADR-002, e a lista vazia é o que permite uma review terminar aprovada sem o
    // modelo inventar achado para preencher a resposta (ADR-004).
    String prompt = promptOf(type).getContentAsString(StandardCharsets.UTF_8);

    assertThat(prompt.toLowerCase(Locale.ROOT)).contains("ignore").contains("vazia");
  }

  @Test
  void cadaPromptDescreveUmFocoDiferente() {
    // Prompt copiado de outro agente é um erro silencioso: o container sobe, analisa e devolve
    // achados do foco errado, que a deduplicação da ADR-008 depois descarta como repetidos.
    String security = contentOf(AgentType.SECURITY);
    String performance = contentOf(AgentType.PERFORMANCE);
    String standards = contentOf(AgentType.STANDARDS);

    assertThat(security).isNotEqualTo(performance).isNotEqualTo(standards);
    assertThat(performance).isNotEqualTo(standards);

    assertThat(security.toLowerCase(Locale.ROOT)).contains("segurança");
    assertThat(performance.toLowerCase(Locale.ROOT)).contains("performance");
    assertThat(standards.toLowerCase(Locale.ROOT)).contains("manutenibilidade");
  }

  @Test
  void oLimiarDeConfiancaEhOQueVeioDaConfiguracao() {
    assertThat(new AgentProperties(AgentType.SECURITY, 0.75).minConfidence()).isEqualTo(0.75);
  }

  private Resource promptOf(AgentType type) {
    return resourceLoader.getResource(
        "classpath:prompts/" + type.name().toLowerCase(Locale.ROOT) + ".st");
  }

  private String contentOf(AgentType type) {
    try {
      return promptOf(type).getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("prompt de " + type + " não pôde ser lido", e);
    }
  }
}
