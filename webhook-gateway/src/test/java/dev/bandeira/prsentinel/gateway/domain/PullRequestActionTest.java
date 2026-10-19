package dev.bandeira.prsentinel.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PullRequestActionTest {

  @ParameterizedTest
  @CsvSource({
    "opened, OPENED",
    "OPENED, OPENED",
    "OpEnEd, OPENED",
    "synchronize, SYNCHRONIZE",
    "SYNCHRONIZE, SYNCHRONIZE",
    "reopened, REOPENED",
    "ReOpEnEd, REOPENED",
    "closed, OTHER",
    "edited, OTHER",
    "labeled, OTHER"
  })
  void mapeiaStringParaAAcaoCorrespondenteDeFormaCaseInsensitive(
      String raw, PullRequestAction expected) {
    assertThat(PullRequestAction.from(raw)).isEqualTo(expected);
  }

  @Test
  void valorNuloViraOther() {
    assertThat(PullRequestAction.from(null)).isEqualTo(PullRequestAction.OTHER);
  }

  @Test
  void openedSynchronizeEReopenedDisparamReview() {
    assertThat(PullRequestAction.OPENED.triggersReview()).isTrue();
    assertThat(PullRequestAction.SYNCHRONIZE.triggersReview()).isTrue();
    assertThat(PullRequestAction.REOPENED.triggersReview()).isTrue();
  }

  @Test
  void otherNaoDisparaReview() {
    assertThat(PullRequestAction.OTHER.triggersReview()).isFalse();
  }
}
