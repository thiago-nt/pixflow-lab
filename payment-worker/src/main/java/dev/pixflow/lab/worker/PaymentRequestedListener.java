package dev.pixflow.lab.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pixflow.lab.contracts.PaymentRequestedEvent;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class PaymentRequestedListener {
    private final PaymentProcessingService processing;
    private final ObjectMapper json;
    private final KafkaTemplate<String,String> kafka;
    private final String dltTopic;
    public PaymentRequestedListener(PaymentProcessingService processing,ObjectMapper json,KafkaTemplate<String,String> kafka,
            @Value("${pixflow.worker.dlt-topic}") String dltTopic) { this.processing=processing;this.json=json;this.kafka=kafka;this.dltTopic=dltTopic; }

    @KafkaListener(topics="${pixflow.worker.topic}", groupId="${pixflow.worker.group-id}", containerFactory="kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String,String> record, Acknowledgment ack) {
        final PaymentRequestedEvent event;
        try {
            event=json.readValue(record.value(),PaymentRequestedEvent.class);
            processing.process(event);
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
            var dlt=new ProducerRecord<String,String>(dltTopic,record.key(),record.value());
            dlt.headers().add("pixflow-error",invalid.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8));
            try { kafka.send(dlt).get(10,TimeUnit.SECONDS); } catch (Exception publishFailure) { throw new IllegalStateException("could not durably publish invalid event to DLT",publishFailure); }
        }
        ack.acknowledge();
    }
}
