package dev.bandeira.prsentinel.agent.infrastructure.llm;

import dev.bandeira.prsentinel.agent.application.port.FindingSource;
import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent.DiffChunk;
import dev.bandeira.prsentinel.common.event.Finding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClientException;

/**
 * Manda um chunk ao LLM com o prompt do agente e devolve os achados crus.
 *
 * <p>Cru de propósito: o limiar de confiança e a validação de forma são regra de negócio e ficam no
 * {@code FindingFilter}, no domínio. Filtrar aqui esconderia do domínio o que o modelo devolveu.
 *
 * <p>Usa {@code SystemMessage}/{@code UserMessage} diretamente, e não template com parâmetros,
 * porque diff de código contém chaves <code>{ }</code> que quebrariam a renderização.
 */
public class SpringAiFindingSource implements FindingSource {

  private static final Logger log = LoggerFactory.getLogger(SpringAiFindingSource.class);

  private static final String FORMAT_CORRECTION =
      """
      Sua resposta anterior não estava no formato pedido. Responda apenas com o JSON do schema,
      sem texto antes nem depois, sem cerca de código e sem comentário.""";

  private final ChatClient chatClient;
  private final String systemPrompt;

  public SpringAiFindingSource(
      ChatClient.Builder chatClientBuilder, AgentType agentType, ResourceLoader resourceLoader) {
    this.chatClient = chatClientBuilder.build();
    this.systemPrompt = loadPrompt(resourceLoader, agentType);
  }

  @Override
  public List<Finding> findIn(DiffChunk chunk) {
    try {
      return ask(chunk, null);
    } catch (RestClientException e) {
      // Erro de transporte ou do provedor: insistir aqui pagaria a requisição duas vezes pelo
      // mesmo motivo, e o retry desse caso é do Spring AI (spring.ai.retry).
      throw e;
    } catch (RuntimeException e) {
      // Sobra o que interessa retentar: resposta que não vira o JSON esperado. Uma segunda
      // chance, agora dizendo ao modelo o que ele errou, resolve a maioria dos casos — e é mais
      // barato que reprovar o agente e perder a review (ADR-004).
      log.warn("Resposta fora do formato para {}; tentando mais uma vez", chunk.file());
      return ask(chunk, FORMAT_CORRECTION);
    }
  }

  private List<Finding> ask(DiffChunk chunk, String correction) {
    String userContent = userContent(chunk);
    if (correction != null) {
      userContent = userContent + "\n\n" + correction;
    }

    AgentOutput output =
        chatClient
            .prompt()
            .messages(new SystemMessage(systemPrompt), new UserMessage(userContent))
            .call()
            .entity(AgentOutput.class);

    return output == null || output.findings() == null ? List.of() : output.findings();
  }

  private static String userContent(DiffChunk chunk) {
    return """
        Arquivo: %s
        Linguagem: %s

        Diff:
        %s
        """
        .formatted(chunk.file(), chunk.language(), chunk.patch());
  }

  /**
   * O prompt é lido uma vez, na construção. Arquivo ausente derruba o boot do container — que é o
   * certo: um agente sem prompt analisaria tudo com o comportamento padrão do modelo e ninguém
   * notaria pela saída.
   */
  private static String loadPrompt(ResourceLoader resourceLoader, AgentType agentType) {
    String location = "classpath:prompts/" + agentType.name().toLowerCase(Locale.ROOT) + ".st";
    try {
      return resourceLoader.getResource(location).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Prompt não encontrado: " + location, e);
    }
  }
}
