package dev.pixflow.lab.worker;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class KafkaWorkerConfiguration {
    @Bean ConcurrentKafkaListenerContainerFactory<String,String> kafkaListenerContainerFactory(ConsumerFactory<String,String> factory) {
        var container=new ConcurrentKafkaListenerContainerFactory<String,String>();
        container.setConsumerFactory(factory);
        container.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        container.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, Long.MAX_VALUE)));
        container.setConcurrency(1);
        return container;
    }
}
