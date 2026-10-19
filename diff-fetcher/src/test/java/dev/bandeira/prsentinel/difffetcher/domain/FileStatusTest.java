package dev.bandeira.prsentinel.difffetcher.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FileStatusTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "added, ADDED",
    "modified, MODIFIED",
    "removed, REMOVED",
    "renamed, RENAMED",
    "copied, COPIED",
    "changed, CHANGED",
    "unchanged, UNCHANGED",
    "MODIFIED, MODIFIED",
    "ReMoVeD, REMOVED"
  })
  void mapeiaOStatusDaApiDeFormaCaseInsensitive(String raw, FileStatus expected) {
    assertThat(FileStatus.from(raw)).isEqualTo(expected);
  }

  @Test
  void statusDesconhecidoViraOther() {
    // O GitHub acrescenta valores com o tempo; um valor novo não pode derrubar a review.
    assertThat(FileStatus.from("inventado")).isEqualTo(FileStatus.OTHER);
  }

  @Test
  void statusAusenteViraOther() {
    assertThat(FileStatus.from(null)).isEqualTo(FileStatus.OTHER);
    assertThat(FileStatus.from("")).isEqualTo(FileStatus.OTHER);
    assertThat(FileStatus.from("   ")).isEqualTo(FileStatus.OTHER);
  }
}
