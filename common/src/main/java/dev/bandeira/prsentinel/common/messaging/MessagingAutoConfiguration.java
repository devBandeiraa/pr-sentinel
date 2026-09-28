package dev.bandeira.prsentinel.common.messaging;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Configuração de mensageria aplicada automaticamente em todo serviço que
 * depende do módulo common.
 */
@AutoConfiguration
public class MessagingAutoConfiguration {

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(Topology.EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(Topology.DLX, true, false);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
