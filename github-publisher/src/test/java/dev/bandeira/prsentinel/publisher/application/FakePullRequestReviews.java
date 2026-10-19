package dev.bandeira.prsentinel.publisher.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.publisher.application.port.PullRequestReviews;
import dev.bandeira.prsentinel.publisher.domain.GithubReviewEvent;
import dev.bandeira.prsentinel.publisher.domain.RenderedReview;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fake da porta de review.
 *
 * <p>Reproduz as duas semânticas que o caso de uso depende: reconhecer review já publicada pelo
 * marcador, e recusar comentários inline só na primeira tentativa — que é como o GitHub se comporta
 * quando a linha de um achado não faz parte do diff.
 */
final class FakePullRequestReviews implements PullRequestReviews {

  private final List<PublishedReview> published = new ArrayList<>();
  private final Set<UUID> alreadyPublished = new HashSet<>();
  private boolean rejectInline;
  private RuntimeException failure;
  private RuntimeException secondAttemptFailure;
  private int attempts;

  record PublishedReview(
      PullRequestRef pullRequest, RenderedReview review, GithubReviewEvent event) {}

  void seedAlreadyPublished(UUID reviewId) {
    alreadyPublished.add(reviewId);
  }

  /** O GitHub recusa a primeira tentativa, a que leva os comentários posicionados. */
  void rejectInlineComments() {
    this.rejectInline = true;
  }

  void failWith(RuntimeException exception) {
    this.failure = exception;
  }

  void failSecondAttemptWith(RuntimeException exception) {
    this.secondAttemptFailure = exception;
  }

  List<PublishedReview> published() {
    return List.copyOf(published);
  }

  int attempts() {
    return attempts;
  }

  /** A review única publicada. Falha se forem zero ou mais de uma. */
  PublishedReview onlyPublished() {
    assertThat(published).hasSize(1);
    return published.get(0);
  }

  @Override
  public boolean alreadyPublished(PullRequestRef pullRequest, UUID reviewId) {
    return alreadyPublished.contains(reviewId);
  }

  @Override
  public void publish(PullRequestRef pullRequest, RenderedReview review, GithubReviewEvent event) {
    attempts++;
    published.add(new PublishedReview(pullRequest, review, event));

    if (failure != null) {
      published.remove(published.size() - 1);
      throw failure;
    }
    if (rejectInline && review.hasInlineComments()) {
      published.remove(published.size() - 1);
      throw new InlineCommentsRejectedException();
    }
    if (secondAttemptFailure != null && attempts == 2) {
      published.remove(published.size() - 1);
      throw secondAttemptFailure;
    }
  }
}
