package dev.pixflow.lab.psp;

import dev.pixflow.lab.contracts.PspChargeRequest;
import dev.pixflow.lab.contracts.PspTransactionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/simulator/transactions")
public class PspSimulatorController {
    private final PspSimulatorService service;
    public PspSimulatorController(PspSimulatorService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<?> charge(@Valid @RequestBody ChargeRequest request) {
        var result = service.charge(request.toCommand());
        if (result.responseLost()) return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(new LostResponse("SIMULATED_RESPONSE_LOST"));
        return ResponseEntity.ok(result.transaction());
    }

    @GetMapping("/by-reference/{reference}")
    public PspTransactionView get(@PathVariable("reference") @NotBlank String reference) {
        return service.findByReference(reference).orElseThrow(() -> new PspTransactionNotFoundException(reference));
    }

    public record ChargeRequest(@jakarta.validation.constraints.NotNull UUID paymentId,
            @NotBlank @jakarta.validation.constraints.Size(max=150) String externalReference,
            @Positive long amountMinor, @Pattern(regexp="BRL") String currency,
            @jakarta.validation.constraints.NotNull dev.pixflow.lab.contracts.SimulationScenario simulationScenario) {
        PspChargeRequest toCommand() { return new PspChargeRequest(paymentId, externalReference, amountMinor, currency, simulationScenario); }
    }
    public record LostResponse(String code) {}
}
