package io.corebanking.api.web;

import io.corebanking.api.config.EodEngines;
import io.corebanking.api.usecase.PeriodEndUseCases;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import io.corebanking.tfj.RunType;
import io.corebanking.tfj.TfjRun;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * L'arrete mensuel (TFM). Son lancement, sa reprise et son annulation se valident a deux : ils
 * ferment ou rouvrent une periode comptable. Sa lecture est ouverte a qui peut le lancer.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/eom/runs")
public class PeriodEndController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final PeriodEndUseCases.Read read;

    public PeriodEndController(UseCaseExecutor executor, EodEngines engines,
                               MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.read = new PeriodEndUseCases.Read(engines, RunType.TFM);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View run(Caller caller, @PathVariable UUID legalEntityId,
                                 @RequestBody Requests.RunEod body) {
        return makerChecker.submit(caller, legalEntityId, "TFM_RUN",
                                   Payloads.of("businessDate", body.businessDate()));
    }

    @GetMapping("/{runId}")
    public TfjRun read(Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID runId) {
        return executor.run(caller, read, new PeriodEndUseCases.Lookup(legalEntityId, runId));
    }

    @PostMapping("/{runId}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View resume(Caller caller, @PathVariable UUID legalEntityId,
                                    @PathVariable UUID runId) {
        return makerChecker.submit(caller, legalEntityId, "TFM_RESUME",
                                   Payloads.of("runId", runId));
    }

    @PostMapping("/{runId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View cancel(Caller caller, @PathVariable UUID legalEntityId,
                                    @PathVariable UUID runId,
                                    @RequestBody Requests.CancelEod body) {
        return makerChecker.submit(caller, legalEntityId, "TFM_CANCEL", Payloads.of(
            "runId", runId, "reversalBookingDate", body.reversalBookingDate(),
            "reason", body.reason()));
    }
}
