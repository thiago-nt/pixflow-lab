package dev.pixflow.lab.worker;

import dev.pixflow.lab.contracts.PspChargeRequest;
import dev.pixflow.lab.contracts.PspTransactionView;
import java.util.Optional;

public interface PspClient {
    PspTransactionView charge(PspChargeRequest request);
    Optional<PspTransactionView> find(String externalReference);
}
