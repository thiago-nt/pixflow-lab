package dev.pixflow.lab.payment.infrastructure.web;

import dev.pixflow.lab.payment.application.PaymentService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentService service; public PaymentController(PaymentService service){this.service=service;}
    @PostMapping public ResponseEntity<PaymentDtos.Response> create(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody PaymentDtos.CreateRequest request){var correlation=MDC.get("correlationId");var result=service.create(key,request.command(),correlation);var body=PaymentDtos.Response.from(result.payment(),!result.created(),correlation);return result.created()?ResponseEntity.created(URI.create("/api/v1/payments/"+result.payment().id())).body(body):ResponseEntity.ok(body);}
    @GetMapping("/{id}") public PaymentDtos.Response get(@PathVariable("id") UUID id){return PaymentDtos.Response.from(service.get(id),false,MDC.get("correlationId"));}
    @GetMapping public Object list(@RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size){return service.list(page,size);}
}
