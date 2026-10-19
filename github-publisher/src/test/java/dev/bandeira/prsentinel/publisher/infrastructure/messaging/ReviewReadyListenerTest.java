package dev.bandeira.prsentinel.publisher.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import dev.bandeira.prsentinel.publisher.application.PublishOutcome;
import dev.bandeira.prsentinel.publisher.application.PublishReviewUseCase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * A decisão de retentar, com o mesmo critério do diff-fetcher — o interlocutor é o mesmo.
 *
 * <p>Insistir num PR fechado ou num App sem permissão de escrita só atrasa a ida para a DLQ; mandar
 * um rate limit para a DLQ joga fora uma review que ia funcionar em um minuto.
 */
class ReviewReadyListenerTest {

  private final PublishReviewUseCase publishReview = mock(PublishReviewUseCase.class);
  private final ReviewReadyListener listener = new ReviewReadyListener(publishReview);

  @ParameterizedTest
  @EnumSource(PublishOutcome.class)
  void qualquerResultadoDoCasoDeUsoTerminaSemExcecao(PublishOutcome outcome) {
    when(publishReview.handle(any())).thenReturn(outcome);

    assertThatCode(() -> listener.onReviewReady(event())).doesNotThrowAnyException();
  }

  @Test
  void delegaOEventoParaOCasoDeUso() {
    when(publishReview.handle(any())).thenReturn(PublishOutcome.PUBLISHED);

    ReviewReadyEvent event = event();
    listener.onReviewReady(event);

    verify(publishReview).handle(event);
  }

  @Test
  void prFechadoOuSemPermissaoVaiDiretoParaADlq() {
    when(publishReview.handle(any()))
        .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "", null, null, null));

    assertThatThrownBy(() -> listener.onReviewReady(event()))
        .isInstanceOf(AmqpRejectAndDontRequeueException.class);
  }

  @Test
  void proibidoSemRateLimitVaiDiretoParaADlq() {
    when(publishReview.handle(any()))
        .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "", null, null, null));

    assertThatThrownBy(() -> listener.onReviewReady(event()))
        .isInstanceOf(AmqpRejectAndDontRequeueException.class);
  }

  @Test
  void rateLimitVoltaComoErroRetentavel() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("x-ratelimit-remaining", "0");
    when(publishReview.handle(any()))
        .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "", headers, null, null));

    assertThatThrownBy(() -> listener.onReviewReady(event()))
        .isInstanceOf(HttpClientErrorException.class)
        .isNotInstanceOf(AmqpRejectAndDontRequeueException.class);
  }

  @Test
  void erroDeServidorVoltaComoErroRetentavel() {
    when(publishReview.handle(any()))
        .thenThrow(
            HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "", null, null, null));

    assertThatThrownBy(() -> listener.onReviewReady(event()))
        .isInstanceOf(HttpServerErrorException.class)
        .isNotInstanceOf(AmqpRejectAndDontRequeueException.class);
  }

  @Test
  void aFilaDoServicoEhADoGithubPublisher() {
    assertThat(ReviewReadyListener.SERVICE).isEqualTo("github-publisher");
  }

  private static ReviewReadyEvent event() {
    return new ReviewReadyEvent(
        UUID.randomUUID(),
        new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123"),
        Verdict.ATTENTION,
        false,
        List.of(),
        List.of(),
        5_000L);
  }
}
