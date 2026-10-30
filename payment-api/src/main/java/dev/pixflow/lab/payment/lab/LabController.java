package dev.pixflow.lab.payment.lab;

import dev.pixflow.lab.payment.application.PaymentService;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/internal/lab")
public class LabController {
    private final PaymentService service; public LabController(PaymentService service){this.service=service;}
    @GetMapping("/payments") public Object list(@RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="50") int size){return service.list(page,Math.min(size,50));}
    @GetMapping("/payments/{id}") public Object detail(@PathVariable("id") UUID id){return service.lab(id);}
    @GetMapping("/capabilities") public Object capabilities(){return java.util.Map.of("paymentApi","IMPLEMENTED","postgresql","IMPLEMENTED","outboxWrite","IMPLEMENTED","kafkaPublish","IMPLEMENTED","worker","IMPLEMENTED","psp","IMPLEMENTED","reconciliation","NOT_IMPLEMENTED");}
}
