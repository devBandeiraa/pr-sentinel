package dev.bandeira.prsentinel.difffetcher.domain;

/**
 * Um arquivo alterado no PR, como o domínio precisa dele: caminho, como mudou e o patch.
 *
 * <p>{@code patch} é nulo de propósito quando o GitHub não manda o campo — é o que acontece com
 * binário, arquivo grande demais e renomeação sem mudança de conteúdo. Nulo aqui não é descuido, é
 * informação: significa "não há texto para revisar" (ADR-007).
 */
public record ChangedFile(String path, FileStatus status, String patch) {

  public ChangedFile {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path do arquivo é obrigatório");
    }
    if (status == null) {
      throw new IllegalArgumentException("status do arquivo é obrigatório");
    }
  }

  public boolean hasPatch() {
    return patch != null && !patch.isBlank();
  }
}
