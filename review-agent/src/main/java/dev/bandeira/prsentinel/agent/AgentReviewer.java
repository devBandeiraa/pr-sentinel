package dev.bandeira.prsentinel.agent;

import dev.bandeira.prsentinel.common.event.DiffReadyEvent.DiffChunk;
import dev.bandeira.prsentinel.common.event.Finding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * Envia um chunk de diff ao LLM com o prompt do agente e devolve os achados.
 *
 * <p>Usa SystemMessage/UserMessage diretamente (em vez de templates com parâmetros) porque diffs de
 * código contêm chaves { } que quebrariam a renderização do template.
 */
@Service
public class AgentReviewer {

  private final ChatClient chatClient;
  private final AgentProperties properties;
  private final String systemPrompt;

  public AgentReviewer(
      ChatClient.Builder chatClientBuilder,
      AgentProperties properties,
      ResourceLoader resourceLoader) {
    this.chatClient = chatClientBuilder.build();
    this.properties = properties;
    this.systemPrompt = load(resourceLoader, properties.promptResource());
  }

  public List<Finding> review(DiffChunk chunk) {
    String userContent =
        """
                Arquivo: %s
                Linguagem: %s

                Diff:
                %s
                """
            .formatted(chunk.file(), chunk.language(), chunk.patch());

    AgentOutput output =
        chatClient
            .prompt()
            .messages(new SystemMessage(systemPrompt), new UserMessage(userContent))
            .call()
            .entity(AgentOutput.class);

    // TODO fase 4: retry com o erro de validação quando o JSON vier inválido
    if (output == null || output.findings() == null) {
      return List.of();
    }
    return output.findings().stream()
        .filter(f -> f.confidence() >= properties.minConfidence())
        .toList();
  }

  private static String load(ResourceLoader loader, String location) {
    try {
      return loader.getResource(location).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Prompt não encontrado: " + location, e);
    }
  }
}
