package dev.pixflow.lab.payment.infrastructure.web;

import dev.pixflow.lab.payment.application.CreatePaymentCommand;
import dev.pixflow.lab.payment.domain.Payment;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class PaymentDtos {
    private PaymentDtos(){}
    public record CreateRequest(@NotBlank @Size(max=100) String accountId,@NotNull @DecimalMin(value="0.01") @Digits(integer=16,fraction=2) BigDecimal amount,@NotBlank String currency,@NotBlank @Size(max=120) String merchantReference){public CreatePaymentCommand command(){return new CreatePaymentCommand(accountId,amount,currency,merchantReference);}}
    public record Response(UUID paymentId,String accountId,BigDecimal amount,String currency,String merchantReference,String status,Instant createdAt,Instant updatedAt,boolean idempotentReplay,String correlationId){static Response from(Payment p,boolean replay,String correlationId){return new Response(p.id(),p.accountId(),p.money().amount(),p.money().currency(),p.merchantReference(),p.status().name(),p.createdAt(),p.updatedAt(),replay,correlationId);}}
}
