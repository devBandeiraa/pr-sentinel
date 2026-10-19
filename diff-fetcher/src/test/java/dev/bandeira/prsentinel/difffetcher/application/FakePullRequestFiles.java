package dev.bandeira.prsentinel.difffetcher.application;

import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.difffetcher.application.port.PullRequestFiles;
import dev.bandeira.prsentinel.difffetcher.domain.ChangedFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Fake da porta de busca. Guarda os PRs consultados para que o teste possa provar que o caso de uso
 * pede os arquivos do PR que veio no evento, e não de outro.
 */
final class FakePullRequestFiles implements PullRequestFiles {

  private final List<PullRequestRef> requested = new ArrayList<>();
  private List<ChangedFile> files = List.of();
  private RuntimeException failure;

  void returning(ChangedFile... changedFiles) {
    this.files = List.of(changedFiles);
  }

  void failWith(RuntimeException exception) {
    this.failure = exception;
  }

  List<PullRequestRef> requested() {
    return List.copyOf(requested);
  }

  @Override
  public List<ChangedFile> fetch(PullRequestRef pullRequest) {
    requested.add(pullRequest);
    if (failure != null) {
      throw failure;
    }
    return files;
  }
}
