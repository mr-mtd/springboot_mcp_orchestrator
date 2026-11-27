package io.github.mrmtd.orchestrator.api;

import io.github.mrmtd.orchestrator.model.CarrierDetails;

final class TransportOrchestratorFixtures {

    private TransportOrchestratorFixtures() {
    }

    static CarrierDetails carrier() {
        return new CarrierDetails(7, "Swift Freight", "MC155433", "DOT7", null, null, null, "TX",
                "SATISFACTORY", true, 3, 40, 3, 92.5);
    }
}
