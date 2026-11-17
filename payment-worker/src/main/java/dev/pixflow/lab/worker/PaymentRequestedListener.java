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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;

@Component
public class PaymentRequestedListener {
    private static final Logger log = LoggerFactory.getLogger(PaymentRequestedListener.class);
    private static final io.opentelemetry.api.trace.Tracer tracer =
            GlobalOpenTelemetry.getTracer("dev.pixflow.lab.payment-worker");
    private final PaymentProcessingService processing;
    private final ObjectMapper json;
    private final KafkaTemplate<String,String> kafka;
    private final String dltTopic;
    private final MeterRegistry meters;
    public PaymentRequestedListener(PaymentProcessingService processing,ObjectMapper json,KafkaTemplate<String,String> kafka,
            @Value("${pixflow.worker.dlt-topic}") String dltTopic, MeterRegistry meters) { this.processing=processing;this.json=json;this.kafka=kafka;this.dltTopic=dltTopic;this.meters=meters; }

    @KafkaListener(topics="${pixflow.worker.topic}", groupId="${pixflow.worker.group-id}", containerFactory="kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String,String> record, Acknowledgment ack) {
        var sample = Timer.start(meters);
        var span = tracer.spanBuilder("payment.consume").startSpan();
        span.setAttribute("operation", "payment.consume");
        span.setAttribute("messaging.kafka.partition", record.partition());
        span.setAttribute("messaging.kafka.offset", record.offset());
        var metricOutcome = "processed";
        final PaymentRequestedEvent event;
        try (var scope = span.makeCurrent()) {
            event=json.readValue(record.value(),PaymentRequestedEvent.class);
            span.setAttribute("payment.id", event.paymentId().toString());
            span.setAttribute("event.id", event.eventId().toString());
            MDC.put("correlationId", event.correlationId());
            MDC.put("paymentId", String.valueOf(event.paymentId()));
            MDC.put("eventId", String.valueOf(event.eventId()));
            processing.process(event);
            span.setAttribute("outcome", "processed");
            log.atInfo().addKeyValue("operation", "payment_event_consume").addKeyValue("outcome", "processed")
                    .log("payment event processed");
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
            metricOutcome = "dlt";
            span.setAttribute("outcome", "dlt");
            span.recordException(invalid);
            span.setStatus(StatusCode.ERROR, invalid.getClass().getSimpleName());
            var dlt=new ProducerRecord<String,String>(dltTopic,record.key(),record.value());
            dlt.headers().add("pixflow-error",invalid.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8));
            try { kafka.send(dlt).get(10,TimeUnit.SECONDS); } catch (Exception publishFailure) { throw new IllegalStateException("could not durably publish invalid event to DLT",publishFailure); }
            log.atWarn().addKeyValue("operation", "payment_event_consume").addKeyValue("outcome", "dlt")
                    .addKeyValue("errorCode", invalid.getClass().getSimpleName()).log("invalid payment event sent to DLT");
        } catch (RuntimeException failure) {
            metricOutcome = "failed";
            span.setAttribute("outcome", "failed");
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR, failure.getClass().getSimpleName());
            log.atError().addKeyValue("operation", "payment_event_consume").addKeyValue("outcome", "failed")
                    .addKeyValue("errorCode", failure.getClass().getSimpleName()).log("payment event processing failed", failure);
            throw failure;
        } finally {
            meters.counter("pixflow.payment.processing.attempts", "outcome", metricOutcome).increment();
            sample.stop(Timer.builder("pixflow.payment.processing.duration").tag("outcome", metricOutcome).register(meters));
            MDC.clear();
            span.end();
        }
        ack.acknowledge();
    }
}
