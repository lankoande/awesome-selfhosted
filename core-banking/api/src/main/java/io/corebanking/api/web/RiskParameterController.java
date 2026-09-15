package io.corebanking.api.web;

import io.corebanking.api.usecase.ParameterUseCases;
import io.corebanking.ledger.store.Database;
import io.corebanking.loan.CollateralPolicy;
import io.corebanking.loan.Contagion;
import io.corebanking.loan.RiskBucket;
import io.corebanking.loan.RiskGrid;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Parametrage du risque : regimes de surete et grilles de classification. Rediges par le risque,
 * actives a deux — ils decident du niveau de provision de tout le portefeuille.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}")
public class RiskParameterController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final ParameterUseCases.DraftCollateralPolicy draftPolicy;
    private final ParameterUseCases.DraftRiskProfile draftProfile;

    public RiskParameterController(UseCaseExecutor executor, Database database,
                                   MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.draftPolicy = new ParameterUseCases.DraftCollateralPolicy(database);
        this.draftProfile = new ParameterUseCases.DraftRiskProfile(database);
    }

    @PostMapping("/collateral-policies")
    @ResponseStatus(HttpStatus.CREATED)
    public Requests.Created draftPolicy(Caller caller, @PathVariable UUID legalEntityId,
                                        @RequestBody Requests.CollateralPolicyDraft body) {
        if (body.eligibleRatePercent() == null || body.maxValuationAgeMonths() == null) {
            throw new IllegalArgumentException(
                "Champs obligatoires absents : eligibleRatePercent, maxValuationAgeMonths");
        }
        CollateralPolicy policy = new CollateralPolicy(body.kind(), body.label(),
                                                       new BigDecimal(body.eligibleRatePercent()),
                                                       body.maxValuationAgeMonths());
        UUID id = executor.run(caller, draftPolicy, new ParameterUseCases.CollateralPolicyDraft(
            legalEntityId, policy, body.validFrom(), body.validTo(), Callers.actorId(caller)));
        return new Requests.Created(id);
    }

    @PostMapping("/collateral-policies/{policyId}/activation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View activatePolicy(Caller caller, @PathVariable UUID legalEntityId,
                                            @PathVariable UUID policyId) {
        return makerChecker.submit(caller, legalEntityId, "COLLATERAL_POLICY_ACTIVATE",
                                   Payloads.of("policyId", policyId));
    }

    @PostMapping("/risk-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public Requests.Created draftProfile(Caller caller, @PathVariable UUID legalEntityId,
                                         @RequestBody Requests.RiskProfileDraft body) {
        UUID id = executor.run(caller, draftProfile, new ParameterUseCases.RiskProfileDraft(
            legalEntityId, body.label(), body.validFrom(), body.validTo(), grid(body.grid()),
            Callers.actorId(caller)));
        return new Requests.Created(id);
    }

    @PostMapping("/risk-profiles/{profileId}/activation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View activateProfile(Caller caller, @PathVariable UUID legalEntityId,
                                             @PathVariable UUID profileId) {
        return makerChecker.submit(caller, legalEntityId, "RISK_PROFILE_ACTIVATE",
                                   Payloads.of("profileId", profileId));
    }

    /** La grille telle que le domaine la valide : rangs contigus, classes sans trou. */
    private static RiskGrid grid(Requests.RiskGridRequest request) {
        if (request == null || request.buckets() == null) {
            throw new IllegalArgumentException("Grille obligatoire, avec ses classes");
        }
        List<RiskBucket> buckets = request.buckets().stream().map(b -> new RiskBucket(
            b.ordinal() == null ? -1 : b.ordinal(), b.code(), b.label(),
            b.fromDays() == null ? -1 : b.fromDays(), b.toDays(),
            new BigDecimal(b.provisionRatePercent() == null ? "0" : b.provisionRatePercent()),
            Boolean.TRUE.equals(b.performing()))).toList();
        Contagion contagion = request.contagion() == null ? Contagion.NONE
                                                          : Contagion.valueOf(request.contagion());
        return new RiskGrid(request.code(), buckets, contagion, request.suspendFromBucket(),
                            request.cureDays() == null ? 0 : request.cureDays());
    }
}
