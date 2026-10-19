package dev.bandeira.prsentinel.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReviewStatusTest {

  @ParameterizedTest
  @EnumSource(
      value = ReviewStatus.class,
      names = {"PENDING", "IN_PROGRESS"})
  void pendenteEEmAndamentoEstaoAbertos(ReviewStatus status) {
    assertThat(status.isOpen()).isTrue();
    assertThat(status.isFinished()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = ReviewStatus.class,
      names = {"COMPLETED", "PARTIAL", "FAILED"})
  void completaParcialEFalhaEstaoEncerrados(ReviewStatus status) {
    assertThat(status.isFinished()).isTrue();
    assertThat(status.isOpen()).isFalse();
  }

  @Test
  void todoStatusEhAbertoOuEncerrado() {
    // Um status novo que não caia num dos dois lados deixaria a varredura de timeout cega para
    // ele: a review ficaria presa sem nunca ser publicada.
    assertThat(Arrays.stream(ReviewStatus.values()).allMatch(s -> s.isOpen() != s.isFinished()))
        .isTrue();
  }

  @Test
  void osNomesCasamComOCheckConstraintDaMigration() {
    // ck_review_status aceita exatamente estes valores. Divergência aqui vira erro de constraint
    // no primeiro UPDATE, em produção.
    assertThat(Arrays.stream(ReviewStatus.values()).map(Enum::name))
        .containsExactlyInAnyOrder("PENDING", "IN_PROGRESS", "COMPLETED", "PARTIAL", "FAILED");
  }
}
