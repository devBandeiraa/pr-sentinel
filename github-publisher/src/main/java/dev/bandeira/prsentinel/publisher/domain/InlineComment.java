package dev.bandeira.prsentinel.publisher.domain;

/**
 * Um comentário posicionado numa linha de arquivo do PR.
 *
 * <p>A linha vem do achado, que vem do LLM — e o GitHub só aceita comentário em linha que faça
 * parte do diff. Quando uma só está fora, a requisição inteira é recusada, e é por isso que existe
 * o caminho de review sem inline (ADR-009).
 */
public record InlineComment(String path, int line, String body) {

  public InlineComment {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path do comentário é obrigatório");
    }
    if (line < 1) {
      throw new IllegalArgumentException("linha do comentário precisa ser positiva: " + line);
    }
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException("corpo do comentário é obrigatório");
    }
  }
}
