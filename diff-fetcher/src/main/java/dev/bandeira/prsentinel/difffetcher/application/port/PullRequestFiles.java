package dev.bandeira.prsentinel.difffetcher.application.port;

import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.difffetcher.domain.ChangedFile;
import java.util.List;

/**
 * Lista os arquivos alterados num PR. Implementado em {@code infrastructure} sobre a API do GitHub.
 */
public interface PullRequestFiles {

  /**
   * Busca todos os arquivos do PR, já paginados.
   *
   * <p>Falha de rede, PR inexistente ou permissão faltando devem lançar, não devolver lista vazia:
   * lista vazia significa "o PR não tem arquivos", e confundir as duas coisas publicaria uma review
   * aprovada para um PR que ninguém conseguiu ler.
   */
  List<ChangedFile> fetch(PullRequestRef pullRequest);
}
