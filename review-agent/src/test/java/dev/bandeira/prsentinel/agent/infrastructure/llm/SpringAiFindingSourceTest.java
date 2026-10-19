package dev.bandeira.prsentinel.agent.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent.DiffChunk;
import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.Severity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.client.ResourceAccessException;

/**
 * O adaptador do LLM.
 *
 * <p>Usa um {@link ChatModel} falso em vez de chamar a API: o CLAUDE.md proíbe chamada real em
 * teste, e o único lugar do projeto que fala com o modelo de verdade é o harness de evals da fase
 * 7. O falso devolve JSON canônico, o que exercita de ponta a ponta o prompt, a conversão da
 * resposta e o retry — sem gastar token.
 */
class SpringAiFindingSourceTest {

  private static final DiffChunk CHUNK =
      new DiffChunk(
          "src/main/java/Login.java",
          "java",
          0,
          "@@ -10,2 +10,3 @@\n String sql = \"SELECT * FROM u WHERE n='\" + nome + \"'\";");

  private static final String UM_ACHADO =
      """
      {
        "findings": [
          {
            "file": "src/main/java/Login.java",
            "line": 11,
            "severity": "CRITICAL",
            "title": "SQL injection por concatenação",
            "explanation": "O nome vem da requisição e entra direto na query.",
            "suggestion": "Use PreparedStatement com parâmetro.",
            "confidence": 0.95
          }
        ]
      }
      """;

  private final FakeChatModel chatModel = new FakeChatModel();

  @Test
  void converteARespostaDoModeloEmAchados() {
    chatModel.respondWith(UM_ACHADO);

    List<Finding> findings = source(AgentType.SECURITY).findIn(CHUNK);

    assertThat(findings).hasSize(1);
    Finding finding = findings.get(0);
    assertThat(finding.file()).isEqualTo("src/main/java/Login.java");
    assertThat(finding.line()).isEqualTo(11);
    assertThat(finding.severity()).isEqualTo(Severity.CRITICAL);
    assertThat(finding.title()).isEqualTo("SQL injection por concatenação");
    assertThat(finding.confidence()).isEqualTo(0.95);
  }

  @Test
  void mandaArquivoLinguagemEPatchNoPrompt() {
    chatModel.respondWith(UM_ACHADO);

    source(AgentType.SECURITY).findIn(CHUNK);

    assertThat(chatModel.lastPrompt())
        .contains("src/main/java/Login.java")
        .contains("java")
        .contains("SELECT * FROM u WHERE");
  }

  @Test
  void usaOPromptDoTipoDeAgenteConfigurado() {
    chatModel.respondWith(UM_ACHADO);

    source(AgentType.SECURITY).findIn(CHUNK);
    assertThat(chatModel.lastPrompt()).contains("segurança");

    chatModel.respondWith(UM_ACHADO);
    source(AgentType.PERFORMANCE).findIn(CHUNK);
    assertThat(chatModel.lastPrompt()).doesNotContain("engenheiro de segurança");
  }

  @Test
  void listaVaziaDoModeloViraListaVazia() {
    chatModel.respondWith("{\"findings\": []}");

    assertThat(source(AgentType.SECURITY).findIn(CHUNK)).isEmpty();
  }

  @Test
  void naoFiltraPorConfianca() {
    // O limiar é regra de negócio e mora no FindingFilter. Filtrar aqui também esconderia do
    // domínio o que o modelo devolveu.
    chatModel.respondWith(UM_ACHADO.replace("0.95", "0.05"));

    assertThat(source(AgentType.SECURITY).findIn(CHUNK)).hasSize(1);
  }

  @Test
  void respostaForaDoFormatoEhTentadaDeNovoApontandoOErro() {
    chatModel.respondWith("Claro! Aqui está a minha análise: o código parece ter um problema.");
    chatModel.respondWith(UM_ACHADO);

    List<Finding> findings = source(AgentType.SECURITY).findIn(CHUNK);

    assertThat(findings).hasSize(1);
    assertThat(chatModel.promptCount()).isEqualTo(2);
    assertThat(chatModel.lastPrompt())
        .as("a segunda tentativa precisa dizer ao modelo o que ele errou")
        .containsIgnoringCase("json");
  }

  @Test
  void respostaForaDoFormatoNasDuasTentativasLanca() {
    chatModel.respondWith("sem json nenhum");
    chatModel.respondWith("de novo sem json");

    assertThatThrownBy(() -> source(AgentType.SECURITY).findIn(CHUNK))
        .isInstanceOf(RuntimeException.class);

    assertThat(chatModel.promptCount()).isEqualTo(2);
  }

  @Test
  void falhaDeTransporteNaoEhRetentadaAqui() {
    // Spring AI já tem retry próprio para erro de transporte (spring.ai.retry). Tentar de novo
    // aqui pagaria a requisição duas vezes pelo mesmo motivo.
    chatModel.failWith(new ResourceAccessException("conexão caiu"));

    assertThatThrownBy(() -> source(AgentType.SECURITY).findIn(CHUNK))
        .isInstanceOf(ResourceAccessException.class);

    assertThat(chatModel.promptCount()).isEqualTo(1);
  }

  private SpringAiFindingSource source(AgentType type) {
    return new SpringAiFindingSource(
        ChatClient.builder(chatModel), type, new DefaultResourceLoader());
  }

  /** {@link ChatModel} falso: responde da fila na ordem e grava os prompts recebidos. */
  private static final class FakeChatModel implements ChatModel {

    private final Deque<String> responses = new ArrayDeque<>();
    private final List<String> prompts = new ArrayList<>();
    private RuntimeException failure;

    void respondWith(String content) {
      responses.add(content);
    }

    void failWith(RuntimeException exception) {
      this.failure = exception;
    }

    String lastPrompt() {
      return prompts.get(prompts.size() - 1);
    }

    int promptCount() {
      return prompts.size();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      prompts.add(prompt.getContents());
      if (failure != null) {
        throw failure;
      }
      String content = responses.isEmpty() ? "" : responses.poll();
      return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
  }
}
