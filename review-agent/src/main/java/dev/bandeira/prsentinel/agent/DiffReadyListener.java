package dev.bandeira.prsentinel.agent;

import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent.Status;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent;
import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.messaging.Topology;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class DiffReadyListener {

    private static final Logger log = LoggerFactory.getLogger(DiffReadyListener.class);

    private final AgentReviewer reviewer;
    private final AgentProperties properties;
    private final RabbitTemplate rabbitTemplate;

    public DiffReadyListener(AgentReviewer reviewer, AgentProperties properties, RabbitTemplate rabbitTemplate) {
        this.reviewer = reviewer;
        this.properties = properties;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = "#{@agentQueueName}")
    public void onDiffReady(DiffReadyEvent event) {
        log.info("Agente {} analisando review={} chunks={}",
                properties.type(), event.reviewId(), event.chunks().size());

        AgentCompletedEvent result;
        try {
            List<Finding> findings = new ArrayList<>();
            for (DiffReadyEvent.DiffChunk chunk : event.chunks()) {
                findings.addAll(reviewer.review(chunk));
            }
            // TODO fase 7: preencher Usage com tokens/latência reais (ChatResponse metadata)
            result = new AgentCompletedEvent(event.reviewId(), properties.type(), Status.SUCCESS,
                    findings, null, null);
        } catch (RuntimeException e) {
            log.error("Agente {} falhou review={}", properties.type(), event.reviewId(), e);
            result = new AgentCompletedEvent(event.reviewId(), properties.type(), Status.FAILED,
                    List.of(), null, e.getMessage());
        }

        rabbitTemplate.convertAndSend(Topology.EXCHANGE, Topology.RK_AGENT_COMPLETED, result);
    }
}
