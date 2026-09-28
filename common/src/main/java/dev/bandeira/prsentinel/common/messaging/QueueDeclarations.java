package dev.bandeira.prsentinel.common.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * Cria uma fila de consumo ligada a uma routing key, com sua DLQ.
 * Mensagens rejeitadas (após os retries) vão para a DLQ via DLX.
 */
public final class QueueDeclarations {

    private QueueDeclarations() {
    }

    public static Declarables consumerQueue(String service, String routingKey) {
        Queue queue = QueueBuilder.durable(Topology.queue(service))
                .deadLetterExchange(Topology.DLX)
                .deadLetterRoutingKey(Topology.dlq(service))
                .build();
        Queue dlq = QueueBuilder.durable(Topology.dlq(service)).build();

        Binding binding = BindingBuilder.bind(queue)
                .to(new TopicExchange(Topology.EXCHANGE))
                .with(routingKey);
        Binding dlqBinding = BindingBuilder.bind(dlq)
                .to(new DirectExchange(Topology.DLX))
                .with(Topology.dlq(service));

        return new Declarables(queue, dlq, binding, dlqBinding);
    }
}
