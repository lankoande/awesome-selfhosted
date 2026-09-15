package io.corebanking.api.web;

import io.corebanking.security.Caller;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Suretes : prise, affectation a un credit, mainlevee — chacune a deux. Une mainlevee decouvre
 * la banque ; une prise surevaluee reduit la provision.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/collaterals")
public class CollateralController {

    private final MakerChecker makerChecker;

    public CollateralController(MakerChecker makerChecker) {
        this.makerChecker = makerChecker;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View register(Caller caller, @PathVariable UUID legalEntityId,
                                      @RequestBody Requests.RegisterCollateral body) {
        return makerChecker.submit(caller, legalEntityId, "COLLATERAL_REGISTER", Payloads.of(
            "customerPartyId", body.customerPartyId(), "assetReference", body.assetReference(),
            "kind", body.kind(), "label", body.label(), "assetValue", body.assetValue(),
            "securedAmount", body.securedAmount(), "currency", body.currency(),
            "rank", body.rank(), "valuedOn", body.valuedOn()));
    }

    @PostMapping("/{collateralId}/allocations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View allocate(Caller caller, @PathVariable UUID legalEntityId,
                                      @PathVariable UUID collateralId,
                                      @RequestBody Requests.AllocateCollateral body) {
        return makerChecker.submit(caller, legalEntityId, "COLLATERAL_ALLOCATE", Payloads.of(
            "collateralId", collateralId, "contractId", body.contractId(),
            "sharePercent", body.sharePercent()));
    }

    @PostMapping("/{collateralId}/release")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View release(Caller caller, @PathVariable UUID legalEntityId,
                                     @PathVariable UUID collateralId,
                                     @RequestBody Requests.ReleaseCollateral body) {
        return makerChecker.submit(caller, legalEntityId, "COLLATERAL_RELEASE", Payloads.of(
            "collateralId", collateralId, "on", body.on()));
    }
}
