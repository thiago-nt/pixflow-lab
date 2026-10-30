package dev.pixflow.lab.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pixflow.lab.contracts.PaymentRequestedEvent;
import dev.pixflow.lab.contracts.SimulationScenario;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

class PaymentRequestedListenerTest {
    @Test void malformedPayloadAcknowledgesOnlyAfterDltSendCompletes() {
        var processing=mock(PaymentProcessingService.class);
        @SuppressWarnings("unchecked") var kafka=(KafkaTemplate<String,String>)mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var ack=mock(Acknowledgment.class);
        var listener=new PaymentRequestedListener(processing,new ObjectMapper(),kafka,"payment.dlt.v1");
        listener.consume(new ConsumerRecord<>("payment.requested.v1",0,2,"invalid-key","{bad-json"),ack);
        verify(kafka).send(any(ProducerRecord.class));
        verify(ack).acknowledge();
    }

    @Test void persistenceFailureLeavesOffsetUnacknowledged() throws Exception {
        var processing=mock(PaymentProcessingService.class);
        @SuppressWarnings("unchecked") var kafka=(KafkaTemplate<String,String>)mock(KafkaTemplate.class);
        var event=new PaymentRequestedEvent(UUID.randomUUID(),UUID.randomUUID(),"payment.requested",1,Instant.now(),"corr",1234,"BRL",SimulationScenario.APPROVED);
        var json=new ObjectMapper().findAndRegisterModules();
        var record=new ConsumerRecord<>("payment.requested.v1",0,3,"payment-key",json.writeValueAsString(event));
        doThrow(new IllegalStateException("database unavailable")).when(processing).process(event);
        var ack=mock(Acknowledgment.class);
        var listener=new PaymentRequestedListener(processing,json,kafka,"payment.dlt.v1");
        assertThatThrownBy(()->listener.consume(record,ack)).isInstanceOf(IllegalStateException.class);
        verify(ack,never()).acknowledge();
        verify(kafka,never()).send(any(ProducerRecord.class));
    }
}
