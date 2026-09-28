package dev.bandeira.prsentinel.difffetcher;

import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ReviewRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(ReviewRequestedListener.class);

    @RabbitListener(queues = "pr-sentinel." + DiffFetcherApplication.SERVICE)
    public void onReviewRequested(ReviewRequestedEvent event) {
        log.info("Buscando diff review={} pr={}", event.reviewId(), event.pullRequest().fullName());

        // TODO fase 3:
        //  1. Obter installation token do GitHub App (JWT assinado com a chave privada)
        //  2. GET /repos/{owner}/{repo}/pulls/{number}/files (paginado)
        //  3. Ignorar lockfiles, arquivos gerados e binários
        //  4. Dividir patches grandes em chunks (limite configurável)
        //  5. Publicar DiffReadyEvent em Topology.RK_DIFF_READY
    }
}
