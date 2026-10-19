package dev.bandeira.prsentinel.difffetcher.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChangedFileTest {

  @Test
  void expoeOsCamposRecebidos() {
    ChangedFile file = new ChangedFile("src/App.java", FileStatus.MODIFIED, "@@ -1 +1 @@\n+ok");

    assertThat(file.path()).isEqualTo("src/App.java");
    assertThat(file.status()).isEqualTo(FileStatus.MODIFIED);
    assertThat(file.patch()).isEqualTo("@@ -1 +1 @@\n+ok");
  }

  @Test
  void temPatchSomenteQuandoOPatchTemConteudo() {
    assertThat(new ChangedFile("a.java", FileStatus.ADDED, "@@ -1 +1 @@").hasPatch()).isTrue();
    assertThat(new ChangedFile("a.png", FileStatus.ADDED, null).hasPatch()).isFalse();
    assertThat(new ChangedFile("a.png", FileStatus.ADDED, "").hasPatch()).isFalse();
    assertThat(new ChangedFile("a.png", FileStatus.ADDED, "   ").hasPatch()).isFalse();
  }

  @Test
  void rejeitaCaminhoAusente() {
    assertThatThrownBy(() -> new ChangedFile(null, FileStatus.ADDED, "@@"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ChangedFile("", FileStatus.ADDED, "@@"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ChangedFile("   ", FileStatus.ADDED, "@@"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejeitaStatusAusente() {
    assertThatThrownBy(() -> new ChangedFile("a.java", null, "@@"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
