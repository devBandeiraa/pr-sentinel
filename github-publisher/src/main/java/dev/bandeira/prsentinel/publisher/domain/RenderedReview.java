package dev.bandeira.prsentinel.publisher.domain;

import java.util.List;

/**
 * A review pronta para ir ao PR: o corpo em Markdown e os comentários posicionados.
 *
 * @param comments vazio quando é o caminho de fallback, em que os achados vão no próprio corpo
 */
public record RenderedReview(String summary, List<InlineComment> comments) {

  public RenderedReview {
    if (summary == null || summary.isBlank()) {
      throw new IllegalArgumentException("resumo da review é obrigatório");
    }
    comments = comments == null ? List.of() : List.copyOf(comments);
  }

  public boolean hasInlineComments() {
    return !comments.isEmpty();
  }
}
