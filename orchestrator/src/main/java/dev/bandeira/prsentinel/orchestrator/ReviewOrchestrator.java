package dev.bandeira.prsentinel.orchestrator;

import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReviewOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrator.class);

  @RabbitListener(queues = "pr-sentinel." + OrchestratorApplication.REVIEWS_SERVICE)
  public void onReviewRequested(ReviewRequestedEvent event) {
    log.info("Registrando review={} pr={}", event.reviewId(), event.pullRequest().fullName());
    // TODO fase 5: persistir review com status PENDING (idempotente por reviewId)
  }

  @RabbitListener(queues = "pr-sentinel." + OrchestratorApplication.RESULTS_SERVICE)
  public void onAgentCompleted(AgentCompletedEvent event) {
    log.info(
        "Agente {} concluiu review={} status={}", event.agent(), event.reviewId(), event.status());
    // TODO fase 5:
    //  1. Salvar resultado do agente (ignorar duplicado: review_id + agent é único)
    //  2. Se os 3 agentes responderam: deduplicar achados, ordenar por severidade,
    //     calcular veredito e publicar ReviewReadyEvent em Topology.RK_REVIEW_READY
  }

  @Scheduled(fixedDelayString = "${orchestrator.timeout-check-interval:30s}")
  public void checkTimeouts() {
    // TODO fase 5: reviews IN_PROGRESS há mais de orchestrator.agent-timeout
    //  viram PARTIAL e são publicadas com os agentes que responderam
  }
}
