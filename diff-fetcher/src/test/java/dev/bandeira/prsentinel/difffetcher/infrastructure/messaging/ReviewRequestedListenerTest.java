package dev.bandeira.prsentinel.difffetcher.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.difffetcher.application.DiffOutcome;
import dev.bandeira.prsentinel.difffetcher.application.FetchDiffUseCase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * A decisão de retentar ou não.
 *
 * <p>É regra de operação, não de negócio: insistir três vezes num PR apagado só atrasa a ida para a
 * DLQ, e mandar um rate limit direto para a DLQ joga fora uma review que ia funcionar daqui a um
 * minuto. Os dois erros chegam como {@code 4xx} do GitHub e precisam de destinos opostos.
 */
class ReviewRequestedListenerTest {

  private final FetchDiffUseCase fetchDiff = mock(FetchDiffUseCase.class);
  private final ReviewRequestedListener listener = new ReviewRequestedListener(fetchDiff);

  @Test
  void delegaOEventoParaOCasoDeUso() {
    when(fetchDiff.handle(any())).thenReturn(new DiffOutcome(3, 1, 2));

    ReviewRequestedEvent event = event();
    listener.onReviewRequested(event);

    verify(fetchDiff).handle(event);
  }

  @Test
  void prInexistenteVaiDiretoParaADlq() {
    when(fetchDiff.handle(any()))
        .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "", null, null, null));

    assertThatThrownBy(() -> listener.onReviewRequested(event()))
        .isInstanceOf(AmqpRejectAndDontRequeueException.class);
  }

  @Test
  void credencialInvalidaVaiDiretoParaADlq() {
    when(fetchDiff.handle(any()))
        .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "", null, null, null));

    assertThatThrownBy(() -> listener.onReviewRequested(event()))
        .isInstanceOf(AmqpRejectAndDontRequeueException.class);
  }

  @Test
  void proibidoSemRateLimitVaiDiretoParaADlq() {
    // 403 sem o cabeçalho de rate limit é App sem permissão: reentregar não resolve.
    when(fetchDiff.handle(any()))
        .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "", null, null, null));

    assertThatThrownBy(() -> listener.onReviewRequested(event()))
        .isInstanceOf(AmqpRejectAndDontRequeueException.class);
  }

  @Test
  void rateLimitEstouradoVoltaComoErroRetentavel() {
    // O GitHub sinaliza rate limit com 403 e x-ratelimit-remaining: 0, não com 429.
    HttpHeaders headers = new HttpHeaders();
    headers.set("x-ratelimit-remaining", "0");
    when(fetchDiff.handle(any()))
        .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "", headers, null, null));

    assertThatThrownBy(() -> listener.onReviewRequested(event()))
        .isInstanceOf(HttpClientErrorException.class)
        .isNotInstanceOf(AmqpRejectAndDontRequeueException.class);
  }

  @Test
  void tooManyRequestsVoltaComoErroRetentavel() {
    when(fetchDiff.handle(any()))
        .thenThrow(
            HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "", null, null, null));

    assertThatThrownBy(() -> listener.onReviewRequested(event()))
        .isInstanceOf(HttpClientErrorException.class)
        .isNotInstanceOf(AmqpRejectAndDontRequeueException.class);
  }

  @Test
  void erroDeServidorDoGitHubVoltaComoErroRetentavel() {
    when(fetchDiff.handle(any()))
        .thenThrow(
            HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "", null, null, null));

    assertThatThrownBy(() -> listener.onReviewRequested(event()))
        .isInstanceOf(HttpServerErrorException.class)
        .isNotInstanceOf(AmqpRejectAndDontRequeueException.class);
  }

  @Test
  void aFilaDoServicoEhADoDiffFetcher() {
    // O nome é compartilhado com a declaração da fila; se divergirem, o serviço sobe e nunca
    // consome nada.
    assertThat(ReviewRequestedListener.SERVICE).isEqualTo("diff-fetcher");
  }

  private static ReviewRequestedEvent event() {
    return new ReviewRequestedEvent(
        UUID.randomUUID(),
        "delivery-1",
        new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123"),
        Instant.parse("2026-10-19T12:00:00Z"));
  }
}
